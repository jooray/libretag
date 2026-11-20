"""nostrmesh: Nostr <-> MeshCore bridge and tools.

    nostrmesh run -c bridge.toml            bridge (mesh->relays on, relays->mesh per config)
    nostrmesh status [-c bridge.toml]       last heartbeat of a running bridge (exit 1 if stale)
    nostrmesh listen -p PORT                print decoded frames heard on the radio
    nostrmesh keygen                        new device key
    nostrmesh dm --key NSEC --to NPUB TEXT -p PORT     send a NIP-04 DM as a device would
    nostrmesh note --key NSEC TEXT -p PORT  send a kind-1 note
    nostrmesh send EVENT.json -p PORT       put an already signed event on the radio
    nostrmesh encode EVENT.json / decode HEX / vectors
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from pathlib import Path

from . import bech32, frag, frames, secp
from .config import Config, RadioCfg
from .errors import FrameError
from .nostr import Event, nip04_dm, sign_event
from .radio import CompanionSerial, Datagram, KissRadio
from .registry import Registry


def make_radio(rc: RadioCfg):
    if not rc.port and rc.adapter != "local_api":
        sys.exit("radio port not set (-p / [radio] port)")
    if rc.adapter == "companion_serial":
        return CompanionSerial(rc.port, rc.channels, rc.baud, rc.add_channels)
    if rc.adapter == "kiss":
        return KissRadio(rc.port, rc.channels, rc.baud)
    if rc.adapter == "local_api":
        from .localapi import Identity, LocalApiRadio

        types = {frames.CHANNEL_NOSTR: [frames.DATA_TYPE_NOSTR], frames.CHANNEL_ROAD: [frames.DATA_TYPE_ROAD],
                 frames.CHANNEL_LIBRETAG: [frames.DATA_TYPE_LIBRETAG]}
        ident = Identity(rc.identity) if rc.identity else Identity()
        return LocalApiRadio(rc.url, {c: types.get(c, [frames.DATA_TYPE_NOSTR]) for c in rc.channels}, ident,
                             region=rc.region or None)
    sys.exit(f"unknown adapter {rc.adapter}")


def radio_from_args(a) -> RadioCfg:
    return RadioCfg(adapter=a.adapter, port=a.port, channels=a.channels.split(","), add_channels=a.add_channels,
                    url=a.url)


def load_key(value: str | None) -> bytes:
    value = value or os.environ.get("NOSTRMESH_NSEC")
    if not value:
        sys.exit("--key (nsec, hex or a file containing one) or NOSTRMESH_NSEC required")
    if Path(value).exists():
        value = Path(value).read_text().strip()
    return bytes.fromhex(bech32.to_hex(value))


def send_encoded(radio, enc: frames.Encoded, gap_s: float = 3.0):
    parts = [enc.frame] if len(enc.frame) <= radio.max_payload else frag.split(enc.frame, radio.max_payload)
    for i, p in enumerate(parts):
        if i:
            time.sleep(gap_s)
        radio.send(enc.channel, enc.data_type, p)
    return len(parts)


def describe(d: frames.Decoded, dg: Datagram | None = None) -> str:
    ev = d.event
    hops = f" hops={dg.hops}" if dg and dg.hops is not None else ""
    return f"{d.profile_name}{hops} id={ev.id[:16]} kind={ev.kind} pubkey={ev.pubkey[:16]} " \
           f"created_at={ev.created_at} content={ev.content[:60]!r}"


# ---------------------------------------------------------------- commands


EXIT_WATCHDOG = 3


def cmd_run(a):
    cfg = Config.load(a.config)
    if a.port:
        cfg.radio.port = a.port
    from .bridge import Bridge
    from .ledger import Ledger
    from .relays import RelayPool, auth_event_signer

    registry = Registry.load(cfg.registry_path) if cfg.registry_path else Registry()
    if not cfg.publish:
        sys.exit("[relays] publish is empty")
    signer = auth_event_signer(load_key(cfg.auth_key)) if cfg.auth_key else None

    async def main() -> int:
        holder = {}
        # always subscribed: LT2 uplink dedup (4243), command mailbox (4244), claims (24244), downlink
        pool = RelayPool(cfg.publish, cfg.subscribe, on_event=lambda ev, url: holder["b"].on_relay_event(ev, url),
                         auth_signer=signer)
        br = Bridge(cfg, make_radio(cfg.radio), pool, Ledger(cfg.ledger_path), registry)
        holder["b"] = br
        pool.start()
        await br.start()
        logging.info("bridge up: uplink %s, downlink %s, LT2 mailbox %s, airtime %.2f + %.2f %%/h, %d relays%s, "
                     "ledger %s, watchdog %s", "on" if cfg.uplink.enabled else "off",
                     "ON" if cfg.downlink.enabled else "off", "on" if cfg.libretag.mailbox else "off",
                     cfg.airtime.budget_pct, cfg.libretag.airtime_pct, len(pool.relays),
                     " (NIP-42 AUTH)" if signer else "", cfg.ledger_path,
                     f"{cfg.watchdog.rx_silence_s / 60:.0f} min" if cfg.watchdog.rx_silence_s > 0 else "off")
        lt = br.lt2
        if lt.pubkey:
            logging.info("bridge key %s, networks %s, receipts %s, peers %d, open entries %s, signed mailbox "
                         "(anchor %.0f s, late %.0f s)",
                         bech32.encode("npub", bytes.fromhex(lt.pubkey)), ",".join(lt.networks) or "-",
                         "on" if cfg.libretag.receipts else "off", len(lt.peers),
                         "on" if cfg.libretag.accept_open else "off", cfg.libretag.anchor_lead_s,
                         cfg.libretag.late_max_s)
        else:
            logging.warning("no [bridge] key: claims go out under fresh keys and count at no other bridge, "
                            "no announcement, no receipts (nostrmesh bridge-key makes one)")
        if cfg.radio.adapter == "local_api" and not cfg.radio.region:
            logging.warning("no [radio] region: commands flood in the node's default scope")
        elif cfg.radio.adapter != "local_api":
            logging.warning("the %s adapter cannot scope a flood: commands flood unscoped", cfg.radio.adapter)
        try:
            await br.fatal.wait()  # the watchdog: the supervisor (launchd, systemd) restarts us
            logging.error("exiting: %s", br.fatal_reason)
            return EXIT_WATCHDOG
        finally:
            br.write_status()
            await br.stop()
            await pool.stop()

    try:
        code = asyncio.run(main())
    except KeyboardInterrupt:
        return
    sys.exit(code)


def cmd_status(a):
    """Print the status file a running bridge writes hourly; exit 1 when it is missing or stale."""
    from .config import DEFAULT_STATUS_PATH

    path = a.path or (Config.load(a.config).status.path if a.config and Path(a.config).exists() else DEFAULT_STATUS_PATH)
    try:
        st = json.loads(Path(path).expanduser().read_text())
    except (OSError, ValueError) as e:
        print(f"no status at {path}: {e}")
        sys.exit(1)
    if a.json:
        print(json.dumps(st, indent=2))
    age = time.time() - st.get("at", 0)
    stale = age > 2 * st.get("interval_s", 3600) + 120
    up = st.get("at", 0) - st.get("started_at", 0)

    def ago(s):
        return f"{s / 3600:.1f} h" if s >= 5400 else f"{s / 60:.0f} min"

    if not a.json:
        print(f"bridge status {path}")
        print(f"  written {ago(age)} ago{'  STALE (bridge down?)' if stale else ''}, up {ago(up)}, "
              f"radio silent {ago(st.get('radio_silence_s', 0))}")
        for name in ("last_hour", "total"):
            h = st.get(name, {})
            print(f"  {name.replace('_', ' '):9}: heard {h.get('heard', 0):.0f}, published {h.get('published', 0):.0f}, "
                  f"dropped {h.get('dropped', 0):.0f}, spam rejected {h.get('spam_rejected', 0):.0f}, "
                  f"sent {h.get('sent_to_radio', 0):.0f}, airtime {h.get('airtime_s', 0):.1f} s")
        w, c = st.get("airtime_window_s", {}), st.get("airtime_cap_s", {})
        print(f"  airtime this hour: main {w.get('main', 0):.1f}/{c.get('main', 0):.1f} s, "
              f"libretag {w.get('libretag', 0):.1f}/{c.get('libretag', 0):.1f} s")
        print(f"  mailbox {st.get('mailbox_tags', 0)} tags, outbox {st.get('outbox', 0)} unpublished events")
    sys.exit(1 if stale else 0)


def cmd_listen(a):
    reg = Registry.load(a.registry) if a.registry else Registry()
    radio = make_radio(radio_from_args(a))
    r = frag.Reassembler()

    def on_rx(dg: Datagram):
        payload = dg.payload
        try:
            if dg.data_type == frames.DATA_TYPE_NOSTR and payload and payload[0] & 0x3F == frames.FRAG:
                payload = r.feed(payload, time.time())
                if payload is None:
                    print(f"{dg.channel} fragment", flush=True)
                    return
            d = frames.decode(payload, dg.data_type, reg.resolve, max_len=frames.MAX_REASSEMBLED)
            print(describe(d, dg), flush=True)
            if a.json:
                print(d.event.to_json(), flush=True)
        except FrameError as e:
            print(f"{dg.channel} data_type={dg.data_type:#06x} {len(dg.payload)} B: {e.code}", flush=True)

    radio.start(on_rx)
    print(f"listening on {a.port} ({a.channels}), ctrl-c to stop", file=sys.stderr)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        radio.close()


def cmd_pairing(a):
    from .localapi import Identity

    ident = Identity(a.identity) if a.identity else Identity()
    print(f"MeshCore Open will ask to allow 'nostrmesh' with fingerprint {ident.fingerprint}")
    print(f"key file: {ident.path}")


def cmd_bridge_key(a):
    """The bridge's stable key (PROTOCOL.md 4.1): written mode 600, never printed; only the npub is shown."""
    p = Path(a.out).expanduser()
    if p.exists():
        from .lt2svc import read_bridge_key

        sk = read_bridge_key(str(p))
        print(f"{p} exists, kept")
    else:
        sk = secp.seckey_from_bytes(os.urandom(32))
        fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(bech32.encode("nsec", sk) + "\n")
        print(f"bridge key written to {p} (mode 600); set [bridge] key = \"{p.name}\"")
    print("npub", bech32.encode("npub", secp.pubkey(sk)))


def cmd_keygen(a):
    sk = secp.seckey_from_bytes(os.urandom(32))
    pk = secp.pubkey(sk)
    if a.out:
        p = Path(a.out)
        p.write_text(bech32.encode("nsec", sk) + "\n")
        p.chmod(0o600)
        print(f"secret key written to {p} (mode 600)")
    else:
        print("nsec", bech32.encode("nsec", sk))
    print("npub", bech32.encode("npub", pk))
    print("hex ", pk.hex())


def _send_event(a, ev: Event, profile: int | None = None):
    enc = frames.encode(ev, profile, can_ref=(lambda _pk: True) if a.ref else None, max_len=None)
    if len(enc.frame) > frames.MAX_RADIO and enc.profile not in frames.DM_PROFILES:
        sys.exit(f"{len(enc.frame)} B does not fit one frame and only DMs are fragmented")
    print(f"{frames.PROFILE_NAMES[enc.profile]} {len(enc.frame)} B, id {ev.id}")
    if a.dry_run:
        print(enc.frame.hex())
        return
    radio = make_radio(radio_from_args(a))
    radio.start(lambda dg: None)
    n = send_encoded(radio, enc)
    print(f"sent ({n} frame{'s' if n > 1 else ''}) on {enc.channel}")
    radio.close()


def cmd_dm(a):
    sk = load_key(a.key)
    ev = nip04_dm(sk, bytes.fromhex(bech32.to_hex(a.to)), a.text, int(time.time()))
    _send_event(a, ev, frames.DM4_REF if a.ref else frames.DM4_FULL)


def cmd_note(a):
    sk = load_key(a.key)
    _send_event(a, sign_event(sk, int(time.time()), 1, [], a.text, os.urandom(32)))


def cmd_send(a):
    ev = Event.from_dict(json.loads(Path(a.event).read_text() if a.event != "-" else sys.stdin.read()))
    _send_event(a, ev)


def cmd_encode(a):
    ev = Event.from_dict(json.loads(Path(a.event).read_text() if a.event != "-" else sys.stdin.read()))
    profile = frames.PROFILE_IDS[a.profile] if a.profile else None
    try:
        enc = frames.encode(ev, profile, can_ref=(lambda _pk: True) if a.ref else None, max_len=None)
    except FrameError as e:
        sys.exit(f"refused: {e}")
    print(json.dumps({"profile": frames.PROFILE_NAMES[enc.profile], "channel": enc.channel,
                      "data_type": f"{enc.data_type:#06x}", "length": len(enc.frame),
                      "fits_radio": len(enc.frame) <= frames.MAX_RADIO,
                      "fits_ble": len(enc.frame) <= frames.MAX_COMPANION_BLE, "frame": enc.frame.hex()}, indent=2))


def cmd_decode(a):
    reg = Registry.load(a.registry) if a.registry else Registry()
    dt = frames.DATA_TYPE_ROAD if a.road else frames.DATA_TYPE_NOSTR
    try:
        d = frames.decode(bytes.fromhex(a.hex), dt, reg.resolve, max_len=frames.MAX_REASSEMBLED)
    except FrameError as e:
        sys.exit(f"refused: {e}")
    print(json.dumps(d.event.to_dict(), indent=2, ensure_ascii=False))


def cmd_vectors(a):
    from .vectors import generate

    if a.road or a.airtime:
        if a.road:
            from .road_vectors import dumps

            out = dumps()
        else:
            from .vectors import airtime_generate

            out = json.dumps(airtime_generate(), indent=2, ensure_ascii=False) + "\n"
        if a.out:
            Path(a.out).write_text(out)
            print(f"wrote {a.out}")
        else:
            sys.stdout.write(out)
        return
    if a.c_header:
        from .vectors import c_header

        Path(a.c_header).write_text(c_header())
        print(f"wrote {a.c_header}")
    out = json.dumps(generate(), indent=2, ensure_ascii=False) + "\n"
    if a.out:
        Path(a.out).write_text(out)
        print(f"wrote {a.out}")
    else:
        sys.stdout.write(out)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="nostrmesh", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def radio_args(p):
        p.add_argument("-p", "--port", default="")
        p.add_argument("--adapter", default="companion_serial", choices=["companion_serial", "kiss", "local_api"])
        p.add_argument("--url", default="ws://127.0.0.1:8787/api/v1", help="local_api: MeshCore Open")
        p.add_argument("--channels", default="#nostr,#roadstr,#libretag")
        p.add_argument("--add-channels", action="store_true", help="add missing hashtag channels to the node")

    p = sub.add_parser("run", help="run the bridge")
    p.add_argument("-c", "--config", default="bridge.toml")
    p.add_argument("-p", "--port", default="", help="override [radio] port")
    p.set_defaults(fn=cmd_run)

    p = sub.add_parser("status", help="last heartbeat of a running bridge")
    p.add_argument("-c", "--config", default="bridge.toml", help="read [status] path from this config")
    p.add_argument("--path", help="status file (default ~/.config/nostrmesh/status.json)")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_status)

    p = sub.add_parser("listen", help="print frames heard on the radio")
    radio_args(p)
    p.add_argument("--registry")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_listen)

    p = sub.add_parser("pairing", help="MeshCore Open pairing fingerprint (local_api)")
    p.add_argument("--identity")
    p.set_defaults(fn=cmd_pairing)

    p = sub.add_parser("bridge-key", help="make the bridge's stable key file (mode 600; prints only the npub)")
    p.add_argument("-o", "--out", default="bridge.key")
    p.set_defaults(fn=cmd_bridge_key)

    p = sub.add_parser("keygen", help="new secp256k1 key for a device")
    p.add_argument("-o", "--out", help="write the nsec to this file (mode 600) instead of printing it")
    p.set_defaults(fn=cmd_keygen)

    for name, fn, helptext in (("dm", cmd_dm, "send a NIP-04 DM"), ("note", cmd_note, "send a kind-1 note")):
        p = sub.add_parser(name, help=helptext)
        radio_args(p)
        p.add_argument("--key", help="nsec / hex / file (or NOSTRMESH_NSEC)")
        if name == "dm":
            p.add_argument("--to", required=True, help="recipient npub or hex")
            p.add_argument("--full", dest="ref", action="store_false",
                           help="dm4.full (full keys, <= 15 B) instead of dm4.ref")
        else:
            p.set_defaults(ref=False)
        p.add_argument("--dry-run", action="store_true", help="print the frame, do not send")
        p.add_argument("text")
        p.set_defaults(fn=fn)

    p = sub.add_parser("send", help="put a signed event (JSON) on the radio")
    radio_args(p)
    p.add_argument("--ref", action="store_true", help="kind 4: use dm4.ref")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("event")
    p.set_defaults(fn=cmd_send)

    p = sub.add_parser("encode", help="event JSON -> frame")
    p.add_argument("--profile", choices=sorted(frames.PROFILE_IDS))
    p.add_argument("--ref", action="store_true")
    p.add_argument("event")
    p.set_defaults(fn=cmd_encode)

    p = sub.add_parser("decode", help="frame hex -> verified event")
    p.add_argument("--road", action="store_true", help="0x0150 #roadstr frame")
    p.add_argument("--registry")
    p.add_argument("hex")
    p.set_defaults(fn=cmd_decode)

    p = sub.add_parser("vectors", help="conformance vectors for the frame profiles")
    p.add_argument("-o", "--out")
    p.add_argument("--c-header", help="also write the C test header (firmware/lt_core/test/nostr_vectors.h)")
    p.add_argument("--road", action="store_true",
                   help="Roadbeacon vectors instead (tests/vectors/road-events-v1.json)")
    p.add_argument("--airtime", action="store_true",
                   help="LoRa airtime vectors instead (docs/vectors/lora-airtime-v1.json)")
    p.set_defaults(fn=cmd_vectors)

    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    a.fn(a)


if __name__ == "__main__":
    main()

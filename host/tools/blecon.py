#!/usr/bin/env python3
"""blecon: bench client for the LibreTag console over Bluetooth (PROTOCOL.md 6.2, firmware 0.8.0).

Scans for console adverts (manufacturer data 0xFFFF, type 0x25), recognises a tag from its usb_key,
connects, runs the handshake (HELLO, AUTH, READY) and then sends console lines in sealed DATA frames.

    uv run --project host --with bleak python host/tools/blecon.py scan [--secs 10]
    uv run --project host --with bleak python host/tools/blecon.py run --k0-file k0.hex -c status -c "history 0"
    uv run --project host --with bleak python host/tools/blecon.py run --k0-file k0.hex          # a terminal
    uv run --project host --with bleak python host/tools/blecon.py run --tag -c status           # a stored full-access share
    uv run --project host --with bleak python host/tools/blecon.py bad-proof [--times 5]      # wrong key
    uv run --project host --with bleak python host/tools/blecon.py hold [--secs 15]           # connect, stay silent
    uv run --project host --with bleak python host/tools/blecon.py replay --k0-file k0.hex    # a recorded AUTH

The key file holds K0 (64 hex) or, with --usb-key-file, usb_key itself; --tag [name|id] takes the
usb_key of a full-access share stored by `libretag share import` (~/.config/libretag/tags). It is read into memory only
and never printed. Without a key `scan` lists every console advert and `bad-proof`/`hold` take the
first one seen (or --addr).
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from bleak import BleakClient, BleakScanner  # noqa: E402

from libretag import lt2  # noqa: E402

SVC = "4c540001-7a1e-4b0c-9d2f-6c6962726574"
RX = "4c540002-7a1e-4b0c-9d2f-6c6962726574"
TX = "4c540003-7a1e-4b0c-9d2f-6c6962726574"


def load_key(args) -> bytes | None:
    if getattr(args, "tag", None) is not None:
        from libretag import share
        try:
            return bytes.fromhex(share.find(args.tag or None).usb_key)
        except LookupError as e:
            sys.exit(str(e))
    if getattr(args, "usb_key_file", None):
        return bytes.fromhex(Path(args.usb_key_file).read_text().strip())
    if getattr(args, "k0_file", None):
        return lt2.Keys(bytes.fromhex(Path(args.k0_file).read_text().strip())).usb_key
    return None


async def find(usb_key: bytes | None, addr: str | None, secs: float, show: bool = False):
    """(device, payload, rssi) of the first console advert that matches the key (or any, without a key)."""
    found: asyncio.Future = asyncio.get_running_loop().create_future()
    seen: dict[str, bytes] = {}

    def cb(dev, adv):
        p = adv.manufacturer_data.get(lt2.BLE_CMD_COMPANY)
        if not p or len(p) != lt2.BCON_ADV_LEN or p[0] != lt2.BCON_ADV_TYPE:
            return
        if show and seen.get(dev.address) != p:
            mine = "" if usb_key is None else (" mine" if lt2.bcon_adv_match(usb_key, p) else " foreign")
            print(f"{time.strftime('%H:%M:%S')} {dev.address} rssi={adv.rssi} left~{p[1] * 2}s "
                  f"salt={p[2:8].hex()} payload={len(p)}B{mine}")
        seen[dev.address] = p
        if addr and dev.address.lower() != addr.lower():
            return
        if usb_key is not None and not lt2.bcon_adv_match(usb_key, p):
            return
        if not found.done():
            found.set_result((dev, p, adv.rssi))

    async with BleakScanner(cb):
        try:
            return await asyncio.wait_for(found, secs)
        except asyncio.TimeoutError:
            return None


class Link:
    def __init__(self, client: BleakClient):
        self.c = client
        self.q: asyncio.Queue[bytes] = asyncio.Queue()
        self.s: lt2.BconSession | None = None
        self.text = asyncio.Queue()

    async def start(self):
        await self.c.start_notify(TX, lambda _ch, data: self.q.put_nowait(bytes(data)))

    async def frame(self, timeout: float) -> bytes:
        return await asyncio.wait_for(self.q.get(), timeout)

    async def handshake(self, usb_key: bytes, app_nonce: bytes | None = None, proof: bytes | None = None):
        hello = await self.frame(5)
        if len(hello) != 18 or hello[0] != lt2.BCON_HELLO or hello[1] != lt2.BCON_VER:
            raise RuntimeError(f"bad HELLO ({len(hello)} B)")
        tn = hello[2:]
        an = app_nonce or os.urandom(16)
        pa = proof or lt2.bcon_proof(usb_key, "a", tn, an)
        auth = bytes([lt2.BCON_AUTH]) + an + pa
        await self.c.write_gatt_char(RX, auth, response=True)
        ready = await self.frame(5)
        if len(ready) != 17 or ready[0] != lt2.BCON_READY:
            raise RuntimeError("bad READY")
        if ready[1:] != lt2.bcon_proof(usb_key, "t", tn, an):
            raise RuntimeError("the tag's proof is wrong: not this tag")
        self.s = lt2.BconSession(usb_key, tn, an)
        return tn, an, auth

    async def send(self, line: str):
        data = (line + "\r").encode()
        chunk = max(1, min(lt2.BCON_DATA_MAX, self.c.mtu_size - 3 - 9))
        for i in range(0, len(data), chunk):
            await self.c.write_gatt_char(RX, self.s.seal(lt2.BCON_A2T, data[i:i + chunk]), response=True)

    async def read_until(self, quiet: float, total: float, end: str | None = None) -> str:
        out, t0 = b"", time.monotonic()
        while time.monotonic() - t0 < total:
            try:
                f = await self.frame(quiet)
            except asyncio.TimeoutError:
                break
            pt = self.s.open(lt2.BCON_T2A, f)
            if pt is None:
                raise RuntimeError("a frame from the tag failed its MAC")
            out += pt
            if end and (b"\n" + end.encode()) in out:
                break
        return out.decode(errors="replace")


async def connect(dev) -> BleakClient:
    c = BleakClient(dev)
    await c.connect(timeout=15)
    return c


async def cmd_scan(args):
    key = load_key(args)
    await find(key, None, args.secs, show=True) if key is None else None
    if key is not None:
        r = await find(key, args.addr, args.secs, show=True)
        print("found" if r else "not seen")


async def cmd_run(args):
    key = load_key(args)
    if key is None:
        sys.exit("run needs --tag, --k0-file or --usb-key-file")
    r = await find(key, args.addr, args.secs)
    if not r:
        sys.exit("tag not seen")
    dev, p, rssi = r
    print(f"found {dev.address} rssi={rssi} left~{p[1] * 2}s")
    t0 = time.monotonic()
    c = await connect(dev)
    try:
        link = Link(c)
        await link.start()
        await link.handshake(key)
        print(f"session open in {time.monotonic() - t0:.1f} s, MTU {c.mtu_size}")
        cmds = args.c or []
        if not cmds:
            loop = asyncio.get_running_loop()
            while True:
                line = await loop.run_in_executor(None, sys.stdin.readline)
                if not line:
                    break
                await link.send(line.rstrip("\r\n"))
                print(await link.read_until(args.quiet, 120), end="")
        for line in cmds:
            t1 = time.monotonic()
            await link.send(line)
            txt = await link.read_until(args.quiet, args.total, "END" if line.split()[0] in
                                        ("history", "log", "blescan", "wifiscan", "meshscan") else None)
            print(f"> {line}  ({len(txt)} B in {time.monotonic() - t1:.1f} s)")
            print(txt, end="" if txt.endswith("\n") else "\n")
        if args.hold_after:
            print(f"idle for {args.hold_after} s ...")
            t1 = time.monotonic()
            txt = await link.read_until(args.hold_after, args.hold_after + 5)
            print(f"after {time.monotonic() - t1:.0f} s: {txt!r} connected={c.is_connected}")
    finally:
        await c.disconnect()


async def cmd_bad_proof(args):
    key = load_key(args)
    for i in range(args.times):
        r = await find(key, args.addr, args.secs)
        if not r:
            print(f"try {i + 1}: no advert (window closed?)")
            return
        dev = r[0]
        c = await connect(dev)
        link = Link(c)
        await link.start()
        t0 = time.monotonic()
        hello = await link.frame(5)
        print(f"try {i + 1}: HELLO {len(hello)} B")
        auth = bytes([lt2.BCON_AUTH]) + os.urandom(16) + os.urandom(16)  # a proof without the key
        try:
            await c.write_gatt_char(RX, auth, response=True)
        except Exception as e:  # noqa: BLE001
            print(f"  write: {e}")
        try:
            f = await link.frame(5)
            print(f"  UNEXPECTED answer {f.hex()}")
        except asyncio.TimeoutError:
            pass
        for _ in range(50):
            if not c.is_connected:
                break
            await asyncio.sleep(0.1)
        print(f"  connected={c.is_connected} after {time.monotonic() - t0:.1f} s")
        await c.disconnect()
        await asyncio.sleep(1.5)
    r = await find(key, args.addr, 8)
    print("advert after the tries:", "present" if r else "gone")


async def cmd_hold(args):
    key = load_key(args)
    r = await find(key, args.addr, args.secs)
    if not r:
        sys.exit("no advert")
    c = await connect(r[0])
    link = Link(c)
    await link.start()
    t0 = time.monotonic()
    await link.frame(5)
    # a write before AUTH that is not AUTH: ignored
    await c.write_gatt_char(RX, b"\x10status\r" + bytes(8), response=True)
    while c.is_connected and time.monotonic() - t0 < args.secs:
        await asyncio.sleep(0.2)
    print(f"connected={c.is_connected}, dropped after {time.monotonic() - t0:.1f} s; frames: {link.q.qsize()}")
    await c.disconnect()


async def cmd_replay(args):
    key = load_key(args)
    r = await find(key, args.addr, args.secs)
    if not r:
        sys.exit("tag not seen")
    c = await connect(r[0])
    link = Link(c)
    await link.start()
    _, _, auth = await link.handshake(key)
    print("first session open, recorded its AUTH")
    await c.disconnect()
    await asyncio.sleep(2)
    r = await find(key, args.addr, args.secs)
    c = await connect(r[0])
    link = Link(c)
    await link.start()
    await link.frame(5)
    await c.write_gatt_char(RX, auth, response=True)
    try:
        f = await link.frame(4)
        print(f"UNEXPECTED: {f.hex()}")
    except asyncio.TimeoutError:
        print("replayed AUTH: no READY")
    for _ in range(50):
        if not c.is_connected:
            break
        await asyncio.sleep(0.1)
    print(f"connected={c.is_connected}")
    await c.disconnect()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["scan", "run", "bad-proof", "hold", "replay"])
    ap.add_argument("--k0-file")
    ap.add_argument("--usb-key-file")
    ap.add_argument("--tag", nargs="?", const="", help="a full-access share stored by `libretag share import` (name or id prefix)")
    ap.add_argument("--addr")
    ap.add_argument("--secs", type=float, default=20)
    ap.add_argument("-c", action="append", help="a console line (repeat)")
    ap.add_argument("--quiet", type=float, default=1.5, help="answer ends after this many quiet seconds")
    ap.add_argument("--total", type=float, default=120)
    ap.add_argument("--times", type=int, default=1)
    ap.add_argument("--hold-after", type=float, default=0)
    args = ap.parse_args()
    asyncio.run({"scan": cmd_scan, "run": cmd_run, "bad-proof": cmd_bad_proof, "hold": cmd_hold,
                 "replay": cmd_replay}[args.what](args))


if __name__ == "__main__":
    main()

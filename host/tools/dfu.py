#!/usr/bin/env python3
"""dfu: firmware package and serial DFU for the Adafruit nRF52 bootloader (Wio Tracker L1).

Reference for the app's Kotlin transport (android/.../usb/dfu/), and a way to compare on a Mac.
Standard library plus pyserial; no adafruit-nrfutil needed (the package it writes is the same
format: manifest.json, application.bin, application.dat with dfu_version 0.5, CRC16 init packet).

    # package from the firmware build (zephyr.hex is checked to start at 0x27000)
    uv run --project host python host/tools/dfu.py pack \
        firmware/zephcore/build-tracker/zephyr/zephyr.hex -o dist/libretag-tracker-wio_tracker_l1-dfu.zip

    uv run --project host python host/tools/dfu.py info dist/libretag-tracker-wio_tracker_l1-dfu.zip

    # serial DFU to a tag that is already in the bootloader (double-tap reset, or `dfu serial`
    # on an unlocked console). Asks before it sends anything.
    uv run --project host python host/tools/dfu.py flash dist/...-dfu.zip -p /dev/cu.usbmodemXXXX

    # the shared test vectors (host/tests/vectors/dfu.json, also read by the Android tests)
    uv run --project host python host/tools/dfu.py vectors > host/tests/vectors/dfu.json

Cross-check with the upstream tool if wanted (same zip, same wire format):
    uvx adafruit-nrfutil dfu genpkg --dev-type 0x0052 --sd-req 0xFFFE --application zephyr.hex x.zip
    uvx adafruit-nrfutil --verbose dfu serial --package x.zip -p /dev/cu.usbmodemXXXX -b 115200 --singlebank

Protocol (Nordic SDK 11 legacy serial DFU as kept by Adafruit_nRF52_Bootloader, see
docs/design/dfu.md): every packet is a reliable HCI vendor packet (type 14) with sequence number,
data integrity check (CRC16-CCITT, init 0xFFFF), SLIP framed (0xC0 ends, 0xDB escapes). The
bootloader answers each good packet with a 4-byte ack whose ack field is the next sequence number
it expects. Packets: START (3) with mode and the three image sizes, INIT (1) with the .dat,
DATA (4) in 512-byte pieces, STOP_DATA (5). After STOP the bootloader checks the CRC, writes its
settings page and resets into the new application.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import struct
import sys
import time
import zipfile
from pathlib import Path

# --- flash layout of the Wio Tracker L1 build (zephcore/boards/common/nrf52_partitions_sdv7.dtsi)
APP_START = 0x27000            # after MBR + S140 7.3.0 (the bootloader's CODE_REGION_1_START)
APP_END = 0xD0000              # code_partition end; storage_partition (key pages) starts here
APP_MAX = APP_END - APP_START  # 0xA9000: an image this size or smaller never erases the keys
RAM_START, RAM_END = 0x20000000, 0x20040000

# --- init packet (dfu_init.c in Adafruit_nRF52_Bootloader)
DEV_TYPE_ADAFRUIT = 0x0052     # checked by the bootloader: anything else is NRF_ERROR_FORBIDDEN
DEV_REV_ANY = 0xFFFF           # only checked for SoftDevice / bootloader updates
APP_VERSION_ANY = 0xFFFFFFFF
SD_REQ_ANY = 0xFFFE            # DFU_SOFTDEVICE_ANY

# --- HCI / SLIP
SLIP_END, SLIP_ESC, SLIP_ESC_END, SLIP_ESC_ESC = 0xC0, 0xDB, 0xDC, 0xDD
HCI_PACKET_TYPE = 14
DFU_INIT_PACKET, DFU_START_PACKET, DFU_DATA_PACKET, DFU_STOP_DATA_PACKET = 1, 3, 4, 5
DFU_UPDATE_MODE_APP = 4
DATA_CHUNK = 512

# --- timing (adafruit-nrfutil dfu_transport_serial.py)
PAGE = 4096
PAGE_ERASE_S = 0.0897
PAGE_WRITE_S = (PAGE / 4) * 0.000100
ACK_TIMEOUT_S = 1.0
TRIES = 4


def crc16(data: bytes, crc: int = 0xFFFF) -> int:
    """CRC16-CCITT as Nordic's crc16_compute (poly 0x1021, init 0xFFFF, no reflection)."""
    for b in data:
        crc = ((crc >> 8) & 0xFF) | ((crc << 8) & 0xFF00)
        crc ^= b
        crc ^= (crc & 0xFF) >> 4
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return crc & 0xFFFF


def init_packet(image: bytes, dev_type=DEV_TYPE_ADAFRUIT, dev_rev=DEV_REV_ANY,
                app_version=APP_VERSION_ANY, sd_req=(SD_REQ_ANY,)) -> bytes:
    """dfu_init_packet_t + CRC16 of the image (dfu_version 0.5, as adafruit-nrfutil genpkg)."""
    return struct.pack(f"<HHIH{len(sd_req)}HH", dev_type, dev_rev, app_version, len(sd_req), *sd_req, crc16(image))


def parse_init_packet(dat: bytes) -> dict:
    if len(dat) < 12:
        raise ValueError("init packet too short")
    dev_type, dev_rev, app_version, n = struct.unpack_from("<HHIH", dat)
    if len(dat) < 10 + 2 * n + 2:
        raise ValueError("init packet too short for its SoftDevice list")
    sd = list(struct.unpack_from(f"<{n}H", dat, 10))
    (crc,) = struct.unpack_from("<H", dat, 10 + 2 * n)
    return {"device_type": dev_type, "device_revision": dev_rev, "application_version": app_version,
            "softdevice_req": sd, "firmware_crc16": crc}


def read_ihex(path: Path) -> tuple[int, bytes]:
    """Intel HEX to (start address, contiguous bytes, gaps 0xFF)."""
    mem: dict[int, int] = {}
    base = 0
    for ln, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        if not line.startswith(":"):
            raise ValueError(f"{path}:{ln}: not an Intel HEX record")
        rec = bytes.fromhex(line[1:])
        if sum(rec) & 0xFF:
            raise ValueError(f"{path}:{ln}: checksum")
        n, addr, typ = rec[0], (rec[1] << 8) | rec[2], rec[3]
        data = rec[4:4 + n]
        if typ == 0:
            for i, b in enumerate(data):
                mem[base + addr + i] = b
        elif typ == 1:
            break
        elif typ == 2:
            base = int.from_bytes(data, "big") << 4
        elif typ == 4:
            base = int.from_bytes(data, "big") << 16
        # 3 and 5 (start address) do not matter for the image
    if not mem:
        raise ValueError(f"{path}: empty")
    lo, hi = min(mem), max(mem) + 1
    return lo, bytes(mem.get(a, 0xFF) for a in range(lo, hi))


def check_image(image: bytes) -> list[str]:
    """Problems with an application image for this board; empty when it is fine."""
    out = []
    if len(image) == 0:
        return ["empty image"]
    if len(image) % 4:
        out.append("size not a multiple of 4 (pack pads it)")
    if len(image) > APP_MAX:
        out.append(f"image {len(image)} B is larger than the application partition {APP_MAX} B: "
                   "the bootloader would erase the key pages")
    if len(image) >= 8:
        sp, reset = struct.unpack_from("<II", image)
        if not (RAM_START < sp <= RAM_END):
            out.append(f"initial stack pointer 0x{sp:08x} is not in RAM: not a Cortex-M image for 0x{APP_START:x}?")
        if not (reset & 1) or not (APP_START <= (reset & ~1) < APP_START + len(image)):
            out.append(f"reset vector 0x{reset:08x} is outside the image: wrong load address?")
    return out


def has_version(image: bytes, version: str) -> bool:
    """LT_FW_VERSION sits in the image as its own C string ("0.7.5\\0")."""
    return b"\0" + version.encode() + b"\0" in image


def build_package(image: bytes, version: str, board: str = "wio_tracker_l1", compress: bool = True) -> bytes:
    if len(image) % 4:
        image = image + b"\xff" * (4 - len(image) % 4)
    dat = init_packet(image)
    meta = parse_init_packet(dat)
    manifest = {"manifest": {"application": {"bin_file": "application.bin", "dat_file": "application.dat",
                                             "init_packet_data": meta}, "dfu_version": 0.5}}
    lt = {"board": board, "version": version, "app_start": APP_START, "size": len(image),
          "sha256": hashlib.sha256(image).hexdigest(), "crc16": meta["firmware_crc16"]}
    buf = io.BytesIO()
    method = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    with zipfile.ZipFile(buf, "w", method) as z:
        for name, data in (("manifest.json", json.dumps(manifest, indent=4, sort_keys=True).encode()),
                           ("application.bin", image), ("application.dat", dat),
                           ("libretag.json", json.dumps(lt, indent=2, sort_keys=True).encode())):
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))  # reproducible
            zi.compress_type = method
            zi.external_attr = 0o644 << 16
            z.writestr(zi, data)
    return buf.getvalue()


def read_package(data: bytes) -> tuple[bytes, bytes, dict]:
    """(image, init packet, libretag.json or {}) after the checks the app makes too."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        m = json.loads(z.read("manifest.json"))["manifest"]
        if set(m) - {"application", "dfu_version"}:
            raise ValueError("package carries more than an application (SoftDevice/bootloader): refused")
        app = m["application"]
        image, dat = z.read(app["bin_file"]), z.read(app["dat_file"])
        lt = json.loads(z.read("libretag.json")) if "libretag.json" in z.namelist() else {}
    ip = parse_init_packet(dat)
    if ip["device_type"] != DEV_TYPE_ADAFRUIT:
        raise ValueError(f"device type 0x{ip['device_type']:04x}, the bootloader wants 0x0052")
    if ip["firmware_crc16"] != crc16(image):
        raise ValueError("init packet CRC does not match the image")
    return image, dat, lt


# --- HCI / SLIP framing

def slip_escape(data: bytes) -> bytes:
    out = bytearray()
    for b in data:
        if b == SLIP_END:
            out += bytes((SLIP_ESC, SLIP_ESC_END))
        elif b == SLIP_ESC:
            out += bytes((SLIP_ESC, SLIP_ESC_ESC))
        else:
            out.append(b)
    return bytes(out)


def slip_unescape(data: bytes) -> bytes:
    out, i = bytearray(), 0
    while i < len(data):
        b = data[i]
        if b == SLIP_ESC:
            if i + 1 >= len(data) or data[i + 1] not in (SLIP_ESC_END, SLIP_ESC_ESC):
                raise ValueError("bad SLIP escape")
            out.append(SLIP_END if data[i + 1] == SLIP_ESC_END else SLIP_ESC)
            i += 2
        else:
            out.append(b)
            i += 1
    return bytes(out)


def hci_header(seq: int, length: int, ack: int | None = None, dip: int = 1, rp: int = 1, typ: int = HCI_PACKET_TYPE) -> bytes:
    ack = (seq + 1) % 8 if ack is None else ack
    h0 = seq | (ack << 3) | (dip << 6) | (rp << 7)
    h1 = typ | ((length & 0x0F) << 4)
    h2 = (length >> 4) & 0xFF
    return bytes((h0, h1, h2, (-(h0 + h1 + h2)) & 0xFF))


def hci_frame(seq: int, payload: bytes) -> bytes:
    """One reliable vendor packet, SLIP framed: C0 | esc(header, payload, crc16) | C0."""
    body = hci_header(seq, len(payload)) + payload
    c = crc16(body)
    return bytes((SLIP_END,)) + slip_escape(body + bytes((c & 0xFF, c >> 8))) + bytes((SLIP_END,))


def ack_number(frame_body: bytes) -> int | None:
    """Ack field of an unescaped frame body (header first), None when it is no valid header."""
    if len(frame_body) < 4 or sum(frame_body[:4]) & 0xFF:
        return None
    return (frame_body[0] >> 3) & 0x07


def start_payload(app_size: int) -> bytes:
    return struct.pack("<IIIII", DFU_START_PACKET, DFU_UPDATE_MODE_APP, 0, 0, app_size)


def init_payload(dat: bytes) -> bytes:
    return struct.pack("<I", DFU_INIT_PACKET) + dat + b"\x00\x00"  # padding as nrfutil


def data_payloads(image: bytes) -> list[bytes]:
    return [struct.pack("<I", DFU_DATA_PACKET) + image[i:i + DATA_CHUNK] for i in range(0, len(image), DATA_CHUNK)]


def stop_payload() -> bytes:
    return struct.pack("<I", DFU_STOP_DATA_PACKET)


def erase_wait_s(size: int) -> float:
    return max(0.5, (size // PAGE + 1) * PAGE_ERASE_S)


# --- serial DFU

class Dfu:
    def __init__(self, port):
        self.port = port
        self.seq = 0
        self.rx = bytearray()

    def _read_frame(self, timeout: float) -> bytes | None:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            b = self.port.read(64)
            if b:
                self.rx += b
            while True:
                try:
                    s = self.rx.index(SLIP_END)
                except ValueError:
                    self.rx.clear()
                    break
                try:
                    e = self.rx.index(SLIP_END, s + 1)
                except ValueError:
                    del self.rx[:s]
                    break
                raw = bytes(self.rx[s + 1:e])
                del self.rx[:e]  # keep the closing C0: it can open the next frame
                if not raw:
                    continue
                try:
                    return slip_unescape(raw)
                except ValueError:
                    continue
        return None

    def send(self, payload: bytes) -> None:
        self.seq = (self.seq + 1) % 8
        frame = hci_frame(self.seq, payload)
        want = (self.seq + 1) % 8
        for _ in range(TRIES):
            self.port.write(frame)
            end = time.monotonic() + ACK_TIMEOUT_S
            while time.monotonic() < end:
                body = self._read_frame(end - time.monotonic())
                if body is not None and ack_number(body) == want:
                    return
        raise RuntimeError(f"no acknowledgement for packet {self.seq} after {TRIES} tries")

    def run(self, image: bytes, dat: bytes, progress=lambda done, total: None) -> None:
        self.send(start_payload(len(image)))
        time.sleep(erase_wait_s(len(image)))  # the bootloader erases now and does not answer
        self.send(init_payload(dat))
        chunks = data_payloads(image)
        for i, p in enumerate(chunks):
            self.send(p)
            if i % 8 == 0:
                time.sleep(PAGE_WRITE_S)  # it writes a page every 8 pieces
            progress(i + 1, len(chunks))
        time.sleep(PAGE_WRITE_S)
        self.send(stop_payload())
        time.sleep(PAGE_ERASE_S + PAGE_WRITE_S)


# --- commands

def cmd_pack(a) -> int:
    src = Path(a.input)
    if src.suffix.lower() == ".hex":
        start, image = read_ihex(src)
        if start != APP_START:
            print(f"error: {src} starts at 0x{start:x}, the application must start at 0x{APP_START:x}", file=sys.stderr)
            return 1
    else:
        image = src.read_bytes()
        print(f"note: a .bin carries no address; assuming 0x{APP_START:x}", file=sys.stderr)
    problems = [p for p in check_image(image) if "pads" not in p]
    if problems:
        for p in problems:
            print("error: " + p, file=sys.stderr)
        return 1
    version = a.version or (Path(__file__).resolve().parents[2] / "firmware" / "VERSION").read_text().strip()
    if not has_version(image, version):
        print(f"warning: the string {version!r} is not in the image: is VERSION in step with the build?", file=sys.stderr)
    zdata = build_package(image, version, a.board)
    out = Path(a.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(zdata)
    print(f"{out}: {len(image)} B application, version {version}, sha256 {hashlib.sha256(zdata).hexdigest()}")
    return 0


def cmd_info(a) -> int:
    data = Path(a.package).read_bytes()
    image, dat, lt = read_package(data)
    print(json.dumps({"package_sha256": hashlib.sha256(data).hexdigest(), "image_size": len(image),
                      "init_packet": parse_init_packet(dat), "libretag": lt,
                      "problems": check_image(image)}, indent=2))
    return 0


def cmd_flash(a) -> int:
    import serial  # pyserial, a dependency of the host project

    image, dat, lt = read_package(Path(a.package).read_bytes())
    problems = check_image(image)
    if problems:
        for p in problems:
            print("error: " + p, file=sys.stderr)
        return 1
    print(f"package: {len(image)} B, version {lt.get('version', '?')}, board {lt.get('board', '?')}")
    print(f"port: {a.port}. The tag must already be in the bootloader (double-tap reset).")
    if not a.yes and input("Send the firmware now? [y/N] ").strip().lower() != "y":
        return 1
    with serial.Serial(a.port, 115200, timeout=0.05) as port:
        time.sleep(0.1)
        d = Dfu(port)
        last = [-1]

        def prog(done, total):
            pct = 100 * done // total
            if pct != last[0]:
                last[0] = pct
                print(f"\r{pct:3d} %", end="", flush=True)

        d.run(image, dat, prog)
    print("\ndone: the tag restarts into the new firmware")
    return 0


def vectors() -> dict:
    """Constructed test vectors shared with the Kotlin implementation."""
    # a synthetic image: vector table for 0x27000 and bytes that need SLIP escaping
    image = struct.pack("<II", 0x20010000, APP_START + 0x101) + bytes((0xC0, 0xDB, 0x00, 0xFF)) * 300 + bytes(range(256))
    image += b"\xff" * (-len(image) % 4)
    dat = init_packet(image)
    frames = []
    seq = 0
    payloads = [("start", start_payload(len(image))), ("init", init_payload(dat))]
    payloads += [(f"data{i}", p) for i, p in enumerate(data_payloads(image))] + [("stop", stop_payload())]
    for name, p in payloads:
        seq = (seq + 1) % 8
        frames.append({"name": name, "seq": seq, "payload": p.hex(), "frame": hci_frame(seq, p).hex()})
    acks = []
    for exp in range(8):
        h0 = exp << 3
        body = bytes((h0, 0, 0, (-h0) & 0xFF))
        acks.append({"expected": exp, "frame": (bytes((SLIP_END,)) + slip_escape(body) + bytes((SLIP_END,))).hex()})
    return {
        "comment": "host/tools/dfu.py vectors: Adafruit nRF52 bootloader serial DFU (HCI/SLIP, init packet 0.5)",
        "crc16": [{"data": b"123456789".hex(), "crc": crc16(b"123456789")},
                  {"data": "", "crc": crc16(b"")},
                  {"data": bytes(range(256)).hex(), "crc": crc16(bytes(range(256)))}],
        "headers": [{"seq": s, "len": n, "header": hci_header(s, n).hex()} for s, n in ((1, 20), (2, 16), (7, 516), (0, 4))],
        "image": image.hex(),
        "init_packet": dat.hex(),
        "frames": frames,
        "acks": acks,
        "erase_wait_ms": int(erase_wait_s(len(image)) * 1000),
        # stored, not deflated: byte-stable across zlib versions
        "package_version": "0.0.1-test",
        "package": base64.b64encode(build_package(image, "0.0.1-test", compress=False)).decode(),
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pack", help="build the DFU .zip from zephyr.hex (or .bin)")
    p.add_argument("input")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("--version", help="firmware version (default: firmware/VERSION)")
    p.add_argument("--board", default="wio_tracker_l1")
    p.set_defaults(fn=cmd_pack)
    p = sub.add_parser("info", help="check and describe a DFU .zip")
    p.add_argument("package")
    p.set_defaults(fn=cmd_info)
    p = sub.add_parser("flash", help="serial DFU to a tag already in the bootloader")
    p.add_argument("package")
    p.add_argument("-p", "--port", required=True)
    p.add_argument("-y", "--yes", action="store_true", help="do not ask")
    p.set_defaults(fn=cmd_flash)
    p = sub.add_parser("vectors", help="print the shared test vectors as JSON")
    p.set_defaults(fn=lambda a: print(json.dumps(vectors(), indent=1)) or 0)
    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())

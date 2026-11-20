"""host/tools/dfu.py: package format, HCI/SLIP framing and the transfer against a simulated bootloader."""
import importlib.util
import json
import struct
import sys
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location("dfu", Path(__file__).parents[1] / "tools" / "dfu.py")
dfu = importlib.util.module_from_spec(_spec)
sys.modules["dfu"] = dfu
_spec.loader.exec_module(dfu)

VEC = json.loads((Path(__file__).parent / "vectors" / "dfu.json").read_text())


def test_vectors_are_current():
    assert dfu.vectors() == VEC


def test_crc16_ccitt():
    assert dfu.crc16(b"123456789") == 0x29B1


def test_init_packet_layout():
    image = b"\x00" * 16
    dat = dfu.init_packet(image)
    # device type 0x0052, rev 0xFFFF, app version 0xFFFFFFFF, one SoftDevice (any), CRC16 of the image
    assert dat == struct.pack("<HHIHHH", 0x52, 0xFFFF, 0xFFFFFFFF, 1, 0xFFFE, dfu.crc16(image))
    assert dfu.parse_init_packet(dat)["firmware_crc16"] == dfu.crc16(image)


def test_header_checksum():
    for h in VEC["headers"]:
        b = bytes.fromhex(h["header"])
        assert sum(b) & 0xFF == 0
        assert b[0] & 7 == h["seq"] and (b[0] >> 3) & 7 == (h["seq"] + 1) % 8
        assert (b[1] >> 4) | (b[2] << 4) == h["len"]


def test_package_roundtrip_and_checks():
    image = struct.pack("<II", 0x20010000, dfu.APP_START + 0x101) + b"\x01" * 1000
    z = dfu.build_package(image, "1.2.3")
    img, dat, lt = dfu.read_package(z)
    assert img == image and lt["version"] == "1.2.3" and lt["size"] == len(image)
    assert dfu.check_image(img) == []
    assert dfu.build_package(image, "1.2.3") == z  # reproducible


def test_too_large_image_is_refused():
    image = struct.pack("<II", 0x20010000, dfu.APP_START + 0x101) + b"\x00" * dfu.APP_MAX
    assert any("key pages" in p for p in dfu.check_image(image))


def test_wrong_load_address_is_caught():
    image = struct.pack("<II", 0x20010000, 0x1001) + b"\x00" * 64
    assert any("reset vector" in p for p in dfu.check_image(image))


class FakeBootloader:
    """The receive side of hci_transport.c + dfu_transport_serial.c, enough to accept an image."""

    def __init__(self, drop_every=0):
        self.expected = 1
        self.rx = bytearray()
        self.out = bytearray()
        self.packets = []
        self.drop_every = drop_every
        self.seen = 0

    def write(self, data):
        self.rx += data
        while True:
            try:
                s = self.rx.index(0xC0)
                e = self.rx.index(0xC0, s + 1)
            except ValueError:
                return
            raw = bytes(self.rx[s + 1:e])
            del self.rx[:e]
            if not raw:
                continue
            self.seen += 1
            if self.drop_every and self.seen % self.drop_every == 0:
                continue  # lost on the wire: no ack
            body = dfu.slip_unescape(raw)
            if sum(body[:4]) & 0xFF or dfu.crc16(body[:-2]) != body[-2] | (body[-1] << 8):
                continue
            if body[0] & 7 == self.expected:
                self.expected = (self.expected + 1) % 8
                self.packets.append(body[4:-2])
            h0 = self.expected << 3
            self.out += b"\xc0" + dfu.slip_escape(bytes((h0, 0, 0, (-h0) & 0xFF))) + b"\xc0"

    def read(self, n):
        b = bytes(self.out[:n])
        del self.out[:n]
        return b


@pytest.mark.parametrize("drop", [0, 7])
def test_transfer_against_fake_bootloader(monkeypatch, drop):
    monkeypatch.setattr(dfu.time, "sleep", lambda s: None)
    monkeypatch.setattr(dfu, "ACK_TIMEOUT_S", 0.01)
    image = bytes.fromhex(VEC["image"])
    dat = bytes.fromhex(VEC["init_packet"])
    bl = FakeBootloader(drop_every=drop)
    dfu.Dfu(bl).run(image, dat)
    kinds = [struct.unpack_from("<I", p)[0] for p in bl.packets]
    assert kinds[0] == dfu.DFU_START_PACKET and kinds[1] == dfu.DFU_INIT_PACKET and kinds[-1] == dfu.DFU_STOP_DATA_PACKET
    assert struct.unpack_from("<IIII", bl.packets[0], 4) == (dfu.DFU_UPDATE_MODE_APP, 0, 0, len(image))
    assert b"".join(p[4:] for p in bl.packets[2:-1]) == image
    assert bl.packets[1][4:-2] == dat

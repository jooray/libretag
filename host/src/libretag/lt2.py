"""LibreTag wire protocol v2 (PROTOCOL.md). Reference implementation; C (firmware/lt_core) and
Kotlin (android) are checked against the vectors this module writes:

    uv run python -m libretag.lt2 vectors > tests/vectors/lt2.json
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import struct
import sys
from dataclasses import dataclass, field
from enum import IntEnum

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

KEY_SIZE = 32
EPOCH_REPORTS = 64
TAG_LEN = 4
MAC_LEN = 8          # plain seal: commands, history records, BLE and Wi-Fi packets
RMAC_LEN = 6         # report v2 (j = 0, 1, on air)
COMMIT_LEN = 3       # report v2: commitment c to the mailbox key pk_n, at P[4:7]
COMMIT_OFF = TAG_LEN
REPORT_PT_LEN = 33
REPORT_PT_LEN_STEALTH = 39
REPORT_P_LEN = TAG_LEN + REPORT_PT_LEN + MAC_LEN  # 45 (v2: 4 + 3 + 32 + 6, the same)
REPORT_P_LEN_STEALTH = TAG_LEN + REPORT_PT_LEN_STEALTH + MAC_LEN  # 51
CMD_PT_LEN = 17
CMD_P_LEN = TAG_LEN + CMD_PT_LEN + MAC_LEN  # 29
MAX_NB = 5
BLE_PT_LEN = 129  # BLE surroundings packet plaintext (PROTOCOL.md 2.3)
BLE_PT_LEN_STEALTH = BLE_PT_LEN + 6
BLE_P_LEN = TAG_LEN + BLE_PT_LEN + MAC_LEN  # 141: channel datagram 3 + 144 = 147 B
BLE_P_LEN_STEALTH = TAG_LEN + BLE_PT_LEN_STEALTH + MAC_LEN  # 147
BLE_MAX_PACKETS = 4
WIFI_PT_LEN = REPORT_PT_LEN  # Wi-Fi packet has the shape of a report

TYPE_REPORT = 0x21
TYPE_COMMAND = 0x22
TYPE_BLE = 0x23
TYPE_WIFI = 0x24

F_HAS_FIX = 0x01
F_CMD_ACK = 0x02
F_LOW_BATT = 0x04
F_NB_DISCOVER = 0x08
F_QUIET = 0x10        # the last report before a quiet stretch (zone or owner present)
F_QUIET_OWNER = 0x20  # with QUIET: the reason is the owner's device nearby (else a home zone)
F_BLE_SUM = 0x40      # bytes 28..30 carry a BLE summary instead of the 5th repeater
F_SYNC = 0x80         # 0.10.0: the tag's schedule anchor is the report before this one (was CHECKS)

MODE_MASK = 0x03      # byte 2 bits 0..1: mode (0.9.4; values 0..3 only, bit 2 was always 0)
B2_BOOT = 0x04        # 0.9.4: byte 2 bit 2, the first report since a boot
B2_PARKED = 0x08      # 0.7: byte 2 bit 3, with QUIET (and not QUIET_OWNER): the reason is parked (STILL)

# 0.9.4: byte 14 of a BOOT report without HAS_FIX (and not in the Wi-Fi seed2 layout): bits 0..2 the
# reset cause, bits 3..7 the resets in a row (capped at 31). The words are the ones `status` prints
# as last_reset= (0 "none": no flag, the nRF52's power-on or brown-out).
BOOT_CAUSES = ("none", "pin", "power", "soft", "debug", "brownout", "watchdog", "crash")
BOOT_RESETS_MAX = 31


def boot_info(cause: str, resets: int) -> int:
    """Byte 14 of a BOOT report without a fix (PROTOCOL.md 2)."""
    return BOOT_CAUSES.index(cause) | (max(0, min(resets, BOOT_RESETS_MAX)) << 3)


def boot_cause_from_rst(rst: int) -> str:
    """The cause word from the LT_RST_* bits, as the firmware's last_reset= picks it: faults first
    (crash, watchdog, brown-out), then pin, debug, power, soft."""
    for bit in (6, 5, 4, 0, 3, 1, 2):
        if rst & (1 << bit):
            return BOOT_CAUSES[bit + 1]
    return "none"

NB_WIFI = 0x80  # byte 15 bit 7: Wi-Fi layout of bytes 16..30

# copies j of one report counter n (PROTOCOL.md 2)
J_HOME = 0
J_BORDER = 1
J_HIST = 2          # history record of a sent report: never on air
J_HIST_SILENT = 3   # history record of a wake-up without a report: never on air
J_WIFI = 4          # Wi-Fi packet after report n (LOST / on command)
J_BLE = 5           # BLE packets k = 0..3 after report n: j = 5 + k
J_NEAR = 9          # 0.10.0: zero-hop copy (the hello)
J_WIDE = 10         # 0.10.0: flood one scope level wider than copy 0 of the same report
J_ROUTED = 11       # 0.10.0: DIRECT along the tag's stored path to a bridge
J_ON_AIR = (J_HOME, J_BORDER, J_NEAR, J_WIDE, J_ROUTED)  # report copies on air: sealing v2
J_EXTRA = (J_WIFI,) + tuple(J_BLE + k for k in range(BLE_MAX_PACKETS))
EXTRA_BACK = 8      # extra copies are looked for only near the newest report
EXTRA_AHEAD = 64

CHANNEL_NAME = "#libretag"
CHANNEL_DATA_TYPE = 0xFF4C
NOSTR_REPORT_KIND = 4243
NOSTR_COMMAND_KIND = 4244
NOSTR_CLAIM_KIND = 24244

WINDOW_BACK = 256
WINDOW_AHEAD = 2048
FIRST_SYNC = 4096


class Mode(IntEnum):
    IDLE = 0
    TRACK = 1
    LISTEN = 2
    LOST = 3


CHECK_OFF = 0xFF  # SET_IDLE check_s: no listening at checks (0 keeps the value)


class Op(IntEnum):
    SET_MODE = 0x01
    SET_IDLE = 0x03
    BUZZ = 0x04
    BLE_SCAN = 0x05
    BLE_CONSOLE = 0x06  # 0.9.2: open the Bluetooth console window (PROTOCOL.md 6.2)


# ---------------------------------------------------------------- primitives

def _u32(n: int) -> bytes:
    return struct.pack("<I", n & 0xFFFFFFFF)


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def _ctr(key: bytes, iv8: bytes, data: bytes) -> bytes:
    c = Cipher(algorithms.AES(key), modes.CTR(iv8 + b"\x00" * (16 - len(iv8)))).encryptor()
    return c.update(data) + c.finalize()


def _sat(v: float, lo: int, hi: int) -> int:
    return max(lo, min(hi, int(round(v))))


# ---------------------------------------------------------------- keys (PROTOCOL.md 1)

SECP256K1_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141


@dataclass(frozen=True)
class Keys:
    """What the owner's app holds: K0, and everything derives from it."""

    k0: bytes

    @property
    def id(self) -> bytes:
        return hmac_sha256(self.k0, b"LT2-id")[:8]

    @property
    def usb_key(self) -> bytes:
        return hmac_sha256(self.k0, b"LT2-usb")

    @property
    def dm_sk(self) -> bytes:
        v = int.from_bytes(hmac_sha256(self.k0, b"LT2-dm"), "big") % SECP256K1_N
        return v.to_bytes(32, "big")

    def view(self, epoch: int) -> bytes:
        return chain(hmac_sha256(self.k0, b"LT2-view"), epoch)

    def cmd(self, epoch: int) -> bytes:
        return chain(hmac_sha256(self.k0, b"LT2-cmd"), epoch)


def chain(start: bytes, epoch: int) -> bytes:
    k = start
    for _ in range(epoch):
        k = hmac_sha256(k, b"LT2-next")
    return k


def epoch(n: int) -> int:
    return n // EPOCH_REPORTS


def usb_auth(usb_key: bytes, challenge: bytes) -> bytes:
    return hmac_sha256(usb_key, b"LT2-auth" + challenge)[:16]


def mailbox_sk(cmd_e: bytes, n: int) -> bytes:
    """Mailbox key of report n (PROTOCOL.md 4): every kind 4244 event for report n is signed with it."""
    v = int.from_bytes(hmac_sha256(cmd_e, b"LT2-mb" + _u32(n)), "big") % SECP256K1_N
    return (v or 1).to_bytes(32, "big")


def mailbox_pk(cmd_e: bytes, n: int) -> bytes:
    """pk_n, x-only (BIP-340), 32 B."""
    from nostrmesh.secp import pubkey
    return pubkey(mailbox_sk(cmd_e, n))


def report_commit(pk: bytes, p: bytes) -> bytes:
    """c = SHA-256("LT2-mbc" || pk_n || x || ct || mac)[0:3]: P without bytes 4..6."""
    return hashlib.sha256(b"LT2-mbc" + pk + p[:TAG_LEN] + p[COMMIT_OFF + COMMIT_LEN:]).digest()[:COMMIT_LEN]


def commit_matches(pk: bytes, p: bytes) -> bool:
    """A bridge's check: does the stored event key pk fit the commitment of report P?"""
    return len(p) in (REPORT_P_LEN, REPORT_P_LEN_STEALTH) and \
        report_commit(pk, p) == bytes(p[COMMIT_OFF:COMMIT_OFF + COMMIT_LEN])


def report_tag(view_e: bytes, n: int, j: int) -> bytes:
    return hmac_sha256(view_e, b"LT2-x" + _u32(n) + bytes([j]))[:TAG_LEN]


def _ck(n: int, k: int) -> bytes:
    """u32(n), and for a check k >= 1 (0.7) also u16(k); k = 0 is the window right after the report."""
    return _u32(n) + (struct.pack("<H", k & 0xFFFF) if k else b"")


def command_tag(cmd_e: bytes, n: int, k: int = 0) -> bytes:
    return hmac_sha256(cmd_e, b"LT2-cx" + _ck(n, k))[:TAG_LEN]


# ---------------------------------------------------------------- SIV seal (PROTOCOL.md 2, 3)

def _seal(kd: bytes, tag: bytes, pt: bytes) -> bytes:
    mac = hmac_sha256(kd[16:], tag + pt)[:MAC_LEN]
    return tag + _ctr(kd[:16], mac, pt) + mac


def _open(kd: bytes, p: bytes) -> bytes | None:
    tag, ct, mac = p[:TAG_LEN], p[TAG_LEN:-MAC_LEN], p[-MAC_LEN:]
    pt = _ctr(kd[:16], mac, ct)
    if not hmac.compare_digest(hmac_sha256(kd[16:], tag + pt)[:MAC_LEN], mac):
        return None
    return pt


def _report_kd(view_e: bytes, n: int, j: int) -> bytes:
    return hmac_sha256(view_e, b"LT2-r" + _u32(n) + bytes([j]))


def _report_kd2(view_e: bytes, n: int, j: int) -> bytes:
    return hmac_sha256(view_e, b"LT2-r2" + _u32(n) + bytes([j]))


def _seal_v2(view_e: bytes, pk: bytes, n: int, j: int, pt1: bytes) -> bytes:
    """Report on air (j = 0, 1): P = x || c || ct || mac6, pt1 = plaintext without the type byte."""
    kd = _report_kd2(view_e, n, j)
    x = report_tag(view_e, n, j)
    mac = hmac_sha256(kd[16:], x + pt1)[:RMAC_LEN]
    ct = _ctr(kd[:16], mac, pt1)
    return x + report_commit(pk, x + b"\0" * COMMIT_LEN + ct + mac) + ct + mac


def _open_v2(view_e: bytes, n: int, j: int, p: bytes) -> bytes | None:
    """The 33 B plaintext (type 0x21 put back in front), or None."""
    kd = _report_kd2(view_e, n, j)
    x, ct, mac = p[:TAG_LEN], p[COMMIT_OFF + COMMIT_LEN:-RMAC_LEN], p[-RMAC_LEN:]
    pt1 = _ctr(kd[:16], mac, ct)
    if not hmac.compare_digest(hmac_sha256(kd[16:], x + pt1)[:RMAC_LEN], mac):
        return None
    return bytes([TYPE_REPORT]) + pt1[:REPORT_PT_LEN - 1]


def _cmd_kd(cmd_e: bytes, n: int, k: int = 0) -> bytes:
    return hmac_sha256(cmd_e, b"LT2-c" + _ck(n, k))


# ---------------------------------------------------------------- report (PROTOCOL.md 2)

def next_in_encode(seconds: int | None) -> int:
    """Byte 31: time to the next wake-up that transmits. 0 = not stated; 1..120 = v x 30 s (to 1 h);
    121..255 = 1 h + (v - 120) x 15 min (to 34.75 h, saturating)."""
    if seconds is None or seconds <= 0:
        return 0
    if seconds <= 3600 + 450:
        return max(1, min(120, (seconds + 15) // 30))
    return min(255, 120 + (seconds - 3600 + 450) // 900)


def next_in_decode(v: int) -> int | None:
    if v == 0:
        return None
    return v * 30 if v <= 120 else 3600 + (v - 120) * 900


def energy_encode(mah: float | None) -> int:
    """Byte 32: charge used since the last full charge, 1 + floor(mAh / 10), 255 = 2540 mAh or more;
    0 = not counted."""
    if mah is None or mah < 0:
        return 0
    return min(255, 1 + int(mah // 10))


def energy_decode(v: int) -> int | None:
    """Lower bound of the used charge in mAh (the true value is within +10 mAh), None = not counted."""
    return None if v == 0 else (v - 1) * 10


def rssi_q(rssi: int) -> int:
    """5-bit RSSI of a Wi-Fi AP: dBm = -95 + 2 q."""
    return max(0, min(31, (rssi + 95 + 1) // 2))


def q_rssi(q: int) -> int:
    return -95 + 2 * (q & 0x1F)


@dataclass
class BleSummary:
    """Bytes 28..30 with F_BLE_SUM: what the last BLE scan saw (PROTOCOL.md 2.3)."""

    devices: int = 0
    stable: int = 0           # public or random static addresses
    stable_rssi: int | None = None  # strongest stable device, dBm


@dataclass
class WifiFields:
    """Bytes 16..30 in the Wi-Fi layout (byte 15 bit 7): one repeater, one full BSSID, one tail."""

    seed: bytes = b"\0" * 6        # full BSSID, as written (aa:bb:...: first byte first)
    seed_rssi: int = -95
    ap_count: int = 0              # APs in the scan, 7 = 7 or more
    tail: bytes | None = None      # last 3 bytes of a second BSSID
    tail_rssi: int = -95
    # A second full BSSID in bytes 8..14 (the position bytes), only in a report without a fix time:
    # a tracker that never had a fix (fw-wifi-esp32.md). Older decoders ignore those bytes then.
    seed2: bytes | None = None
    seed2_rssi: int = -95
    # A second tail in place of the repeater (bytes 16..18, meta in byte 30 with bit 7 set): sent
    # when the scan has at least two tail candidates; the report then carries no repeater entry.
    tail2: bytes | None = None
    tail2_rssi: int = -95


@dataclass
class Report:
    has_fix: bool = False
    fix_time: int = 0  # unix s of the fix (with has_fix) or of the last fix; 0 = none
    lat: float = 0.0
    lon: float = 0.0
    hacc_m: int = 255
    battery_pct: int | None = None
    mode: Mode = Mode.IDLE
    cmd_ack: bool = False
    cmd_seq: int = 0
    low_batt: bool = False
    nb_total: int = 0
    nb_discover: bool = False
    neighbours: list[tuple[bytes, float]] = field(default_factory=list)  # (2-byte key prefix, SNR dB)
    quiet: bool = False
    quiet_owner: bool = False        # with quiet: owner present (else zone, or parked)
    quiet_parked: bool = False       # 0.7, with quiet and not quiet_owner: parked (byte 2 bit 3)
    sync: bool = False               # 0.10.0: the schedule anchor is report n - 1 (flags bit 7, SYNC)
    next_in_s: int | None = None     # coded in 30 s / 15 min steps (next_in_encode)
    energy_mah: float | None = None  # coded in 10 mAh steps (energy_encode)
    ble: BleSummary | None = None    # at most 4 repeaters then
    wifi: WifiFields | None = None   # at most 1 repeater then; excludes ble
    boot: bool = False               # 0.9.4: the first report since a boot (byte 2 bit 2)
    # 0.9.4, with boot: the reset cause word and resets in a row, carried in byte 14 only when the
    # report has no fix and is not in the Wi-Fi seed2 layout (else None: the byte is the accuracy)
    boot_cause: str | None = None
    boot_resets: int | None = None

    def boot_in_byte14(self) -> bool:
        seed2 = self.wifi is not None and self.wifi.seed2 is not None and not self.fix_time
        return self.boot and not self.has_fix and not seed2

    def encode(self) -> bytes:
        flags = (F_HAS_FIX if self.has_fix else 0) | (F_CMD_ACK if self.cmd_ack else 0) \
            | (F_LOW_BATT if self.low_batt else 0) | (F_NB_DISCOVER if self.nb_discover else 0) \
            | (F_QUIET if self.quiet else 0) | (F_QUIET_OWNER if self.quiet and self.quiet_owner else 0) \
            | (F_BLE_SUM if self.ble is not None and self.wifi is None else 0) | (F_SYNC if self.sync else 0)
        parked = self.quiet and self.quiet_parked and not self.quiet_owner
        max_nb = (0 if self.wifi.tail2 is not None else 1) if self.wifi is not None else 4 if flags & F_BLE_SUM else MAX_NB
        nbs = self.neighbours[:max_nb]
        out = bytearray([TYPE_REPORT, flags,
                         (int(self.mode) & MODE_MASK) | (B2_BOOT if self.boot else 0) | (B2_PARKED if parked else 0)
                         | ((self.cmd_seq & 0x0F) << 4),
                         255 if self.battery_pct is None else _sat(self.battery_pct, 0, 100)])
        out += struct.pack("<I", self.fix_time & 0xFFFFFFFF)
        lat24 = _sat(self.lat * (1 << 23) / 90, -(1 << 23), (1 << 23) - 1) if self.fix_time else 0
        lon24 = _sat(self.lon * (1 << 23) / 180, -(1 << 23), (1 << 23) - 1) if self.fix_time else 0
        if self.wifi is not None and self.wifi.seed2 is not None and not self.fix_time:
            out += bytes(self.wifi.seed2[:6]) + bytes([rssi_q(self.wifi.seed2_rssi) | 0x80])
        else:
            out += (lat24 & 0xFFFFFF).to_bytes(3, "little") + (lon24 & 0xFFFFFF).to_bytes(3, "little")
            out.append(boot_info(self.boot_cause or "none", self.boot_resets or 0) if self.boot_in_byte14()
                       else _sat(self.hacc_m, 0, 255))
        out.append(min(self.nb_total, 15) | (len(nbs) << 4) | (NB_WIFI if self.wifi is not None else 0))
        body = bytearray(15)
        for i, (h, snr) in enumerate(nbs):
            body[3 * i:3 * i + 3] = bytes(h[:2]) + struct.pack("<b", _sat(snr * 4, -128, 127))
        if self.wifi is not None:
            w = self.wifi
            body[3:9] = bytes(w.seed[:6])
            body[9] = rssi_q(w.seed_rssi) | (min(w.ap_count, 7) << 5)
            if w.tail is not None:
                body[10:13] = bytes(w.tail[-3:])
                body[13] = rssi_q(w.tail_rssi) | 0x80
            if w.tail2 is not None:
                body[0:3] = bytes(w.tail2[-3:])
                body[14] = rssi_q(w.tail2_rssi) | 0x80
        elif self.ble is not None:
            b = self.ble
            body[12] = min(b.devices, 255)
            body[13] = min(b.stable, 255)
            body[14] = 0 if b.stable_rssi is None else _sat(b.stable_rssi, -128, -1) & 0xFF
        out += body
        out.append(next_in_encode(self.next_in_s))
        out.append(energy_encode(self.energy_mah))
        assert len(out) == REPORT_PT_LEN
        return bytes(out)

    @classmethod
    def decode(cls, pt: bytes) -> "Report":
        if len(pt) not in (REPORT_PT_LEN, REPORT_PT_LEN_STEALTH) or pt[0] != TYPE_REPORT:
            raise ValueError("not an LT2 report")

        def s24(b: bytes) -> int:
            v = int.from_bytes(b, "little")
            return v - (1 << 24) if v & 0x800000 else v

        flags, mode_b, batt = pt[1], pt[2], pt[3]
        t = struct.unpack_from("<I", pt, 4)[0]
        counts = pt[15]
        wifi_l = bool(counts & NB_WIFI)
        ble_l = bool(flags & F_BLE_SUM) and not wifi_l
        tail2_l = wifi_l and bool(pt[30] & 0x80)
        max_nb = (0 if tail2_l else 1) if wifi_l else 4 if ble_l else MAX_NB
        nbs = []
        for i in range(min((counts >> 4) & 0x07, max_nb)):
            o = 16 + 3 * i
            nbs.append((bytes(pt[o:o + 2]), struct.unpack_from("<b", pt, o + 2)[0] / 4))
        wifi = ble = None
        if wifi_l:
            wifi = WifiFields(seed=bytes(pt[19:25]), seed_rssi=q_rssi(pt[25]), ap_count=pt[25] >> 5,
                              tail=bytes(pt[26:29]) if pt[29] & 0x80 else None,
                              tail_rssi=q_rssi(pt[29]) if pt[29] & 0x80 else -95)
            if tail2_l:
                wifi.tail2, wifi.tail2_rssi = bytes(pt[16:19]), q_rssi(pt[30])
            if not t and any(pt[8:14]):
                wifi.seed2, wifi.seed2_rssi = bytes(pt[8:14]), q_rssi(pt[14])
        elif ble_l:
            ble = BleSummary(devices=pt[28], stable=pt[29],
                             stable_rssi=None if pt[30] == 0 else struct.unpack_from("<b", pt, 30)[0])
        boot = bool(mode_b & B2_BOOT)
        info = boot and not flags & F_HAS_FIX and not (wifi is not None and wifi.seed2 is not None)
        return cls(
            boot=boot, boot_cause=BOOT_CAUSES[pt[14] & 0x07] if info else None,
            boot_resets=pt[14] >> 3 if info else None,
            has_fix=bool(flags & F_HAS_FIX), fix_time=t,
            lat=s24(pt[8:11]) * 90 / (1 << 23) if t else 0.0,
            lon=s24(pt[11:14]) * 180 / (1 << 23) if t else 0.0,
            hacc_m=255 if info or (wifi is not None and wifi.seed2 is not None) else pt[14],
            battery_pct=None if batt == 255 else batt,
            mode=Mode(mode_b & MODE_MASK) if (mode_b & MODE_MASK) in Mode._value2member_map_ else Mode.IDLE,
            cmd_ack=bool(flags & F_CMD_ACK), cmd_seq=mode_b >> 4, low_batt=bool(flags & F_LOW_BATT),
            nb_total=counts & 0x0F, nb_discover=bool(flags & F_NB_DISCOVER), neighbours=nbs,
            quiet=bool(flags & F_QUIET), quiet_owner=bool(flags & F_QUIET_OWNER),
            quiet_parked=bool(flags & F_QUIET) and not flags & F_QUIET_OWNER and bool(mode_b & B2_PARKED),
            sync=bool(flags & F_SYNC),
            next_in_s=next_in_decode(pt[31]), energy_mah=energy_decode(pt[32]), ble=ble, wifi=wifi,
        )


def seal_report(view_e: bytes, n: int, j: int, report: Report, stealth: bool = False,
                pk: bytes | None = None) -> bytes:
    """j in J_ON_AIR (0, 1, 9, 10, 11, on air): v2 sealing with the commitment to pk = pk_n (required).
    j = 2, 3 (history records, never on air): the plain seal with the type byte and an 8 B mac."""
    pt = report.encode() + (b"\0" * 6 if stealth else b"")
    if j in J_ON_AIR:
        if pk is None:
            raise ValueError("report on air needs pk_n (mailbox_pk)")
        return _seal_v2(view_e, pk, n, j, pt[1:])
    return _seal(_report_kd(view_e, n, j), report_tag(view_e, n, j), pt)


def seal_report_keys(keys: "Keys", n: int, j: int, report: Report, stealth: bool = False) -> bytes:
    """seal_report with the view and mailbox keys of report n (what the tracker does)."""
    e = epoch(n)
    pk = mailbox_pk(keys.cmd(e), n) if j in J_ON_AIR else None
    return seal_report(keys.view(e), n, j, report, stealth=stealth, pk=pk)


def open_report(view_e: bytes, n: int, j: int, p: bytes) -> Report | None:
    if len(p) not in (REPORT_P_LEN, REPORT_P_LEN_STEALTH) or p[:TAG_LEN] != report_tag(view_e, n, j):
        return None
    pt = _open_v2(view_e, n, j, p) if j in J_ON_AIR else _open(_report_kd(view_e, n, j), p)
    if pt is None:
        return None
    try:
        return Report.decode(pt)
    except ValueError:
        return None


# ---------------------------------------------------------------- extra packets after a report (PROTOCOL.md 2.3, 2.4)

def seal_extra(view_e: bytes, n: int, j: int, pt: bytes, stealth: bool = False) -> bytes:
    """A BLE or Wi-Fi packet: sealed like report n, copy j (J_WIFI, J_BLE + k)."""
    return _seal(_report_kd(view_e, n, j), report_tag(view_e, n, j), pt + (b"\0" * 6 if stealth else b""))


def open_extra(view_e: bytes, n: int, j: int, p: bytes) -> "BlePacket | WifiPacket | None":
    if p[:TAG_LEN] != report_tag(view_e, n, j):
        return None
    if j == J_WIFI and len(p) in (REPORT_P_LEN, REPORT_P_LEN_STEALTH):
        pt = _open(_report_kd(view_e, n, j), p)
        return WifiPacket.decode(pt) if pt is not None and pt[0] == TYPE_WIFI else None
    if J_BLE <= j < J_BLE + BLE_MAX_PACKETS and len(p) in (BLE_P_LEN, BLE_P_LEN_STEALTH):
        pt = _open(_report_kd(view_e, n, j), p)
        return BlePacket.decode(pt) if pt is not None and pt[0] == TYPE_BLE else None
    return None


ADDR_PUBLIC, ADDR_STATIC, ADDR_RPA, ADDR_NRPA = 0, 1, 2, 3
BI_CONNECTABLE = 0x04
BI_SCAN_RSP = 0x08
BI_ANCHOR = 0x10
BLE_HDR_LEN = 8
BLE_REC_MIN = 10  # len, address 6, info, rssi, heard


@dataclass
class BleDevice:
    """One record of a BLE packet. `ad` holds AD structures as on air (len, type, data), most
    important first; the packer drops trailing ones that do not fit."""

    addr: bytes                 # 6 bytes as written (first byte = most significant)
    kind: int = ADDR_PUBLIC
    rssi: int = -127
    heard: int = 1
    connectable: bool = False
    scan_rsp: bool = False
    anchor: bool = False
    ad: bytes = b""

    def info(self) -> int:
        return (self.kind & 3) | (BI_CONNECTABLE if self.connectable else 0) | \
            (BI_SCAN_RSP if self.scan_rsp else 0) | (BI_ANCHOR if self.anchor else 0)

    def ads(self) -> list[tuple[int, bytes]]:
        out, i = [], 0
        while i < len(self.ad) and self.ad[i]:
            ln = self.ad[i]
            if i + 1 + ln > len(self.ad):
                break
            out.append((self.ad[i + 1], bytes(self.ad[i + 2:i + 1 + ln])))
            i += 1 + ln
        return out

    @property
    def name(self) -> str | None:
        for t, d in self.ads():
            if t in (0x08, 0x09):
                return d.decode("utf-8", "replace")
        return None

    @property
    def company(self) -> int | None:
        for t, d in self.ads():
            if t == 0xFF and len(d) >= 2:
                return d[0] | (d[1] << 8)
        return None


def ad(t: int, data: bytes) -> bytes:
    return bytes([len(data) + 1, t]) + data


@dataclass
class BleSurvey:
    """Header of every BLE packet: the whole scan in numbers."""

    seconds: int = 0
    active: bool = False
    devices: int = 0
    stable: int = 0
    rotating: int = 0
    rotating_apple: int = 0
    rotating_microsoft: int = 0


@dataclass
class BlePacket:
    k: int
    total: int
    truncated: bool
    survey: BleSurvey
    devices: list[BleDevice]

    @classmethod
    def decode(cls, pt: bytes) -> "BlePacket":
        if len(pt) not in (BLE_PT_LEN, BLE_PT_LEN_STEALTH) or pt[0] != TYPE_BLE:
            raise ValueError("not an LT2 BLE packet")
        b1 = pt[1]
        sv = BleSurvey(seconds=pt[2], active=bool(b1 & 0x10), devices=pt[3], stable=pt[4], rotating=pt[5],
                       rotating_apple=pt[6], rotating_microsoft=pt[7])
        devs, i = [], BLE_HDR_LEN
        while i < BLE_PT_LEN and pt[i]:
            ln = pt[i]
            if ln < BLE_REC_MIN - 1 or i + 1 + ln > BLE_PT_LEN:
                break
            r = pt[i + 1:i + 1 + ln]
            info = r[6]
            devs.append(BleDevice(addr=bytes(r[0:6]), kind=info & 3, rssi=struct.unpack_from("<b", r, 7)[0],
                                  heard=r[8], connectable=bool(info & BI_CONNECTABLE),
                                  scan_rsp=bool(info & BI_SCAN_RSP), anchor=bool(info & BI_ANCHOR),
                                  ad=bytes(r[9:])))
            i += 1 + ln
        return cls(k=b1 & 3, total=((b1 >> 2) & 3) + 1, truncated=bool(b1 & 0x20), survey=sv, devices=devs)


def _ble_record(d: BleDevice, room: int) -> bytes | None:
    """The record for d in at most `room` bytes, dropping trailing AD structures; None if not even
    the fixed part fits."""
    ads = []
    i = 0
    while i < len(d.ad) and d.ad[i]:
        ln = d.ad[i]
        if i + 1 + ln > len(d.ad):
            break
        ads.append(d.ad[i:i + 1 + ln])
        i += 1 + ln
    while True:
        body = bytes(d.addr[:6]) + bytes([d.info(), _sat(d.rssi, -128, 127) & 0xFF, min(d.heard, 255)]) \
            + b"".join(ads)
        if 1 + len(body) <= room:
            return bytes([len(body)]) + body
        if not ads:
            return None
        ads.pop()


def ble_pack(survey: BleSurvey, devices: list[BleDevice], max_packets: int) -> list[bytes]:
    """Plaintexts of the BLE packets: devices in the given order (most important first), each record
    whole in one packet, trailing AD structures dropped where a record would not fit otherwise.
    Every packet repeats the survey header, so any one of them is readable alone."""
    max_packets = max(1, min(BLE_MAX_PACKETS, max_packets))
    pkts: list[bytearray] = []
    cur = bytearray()
    left = list(devices)
    while left and len(pkts) < max_packets:
        rec = _ble_record(left[0], BLE_PT_LEN - BLE_HDR_LEN - len(cur))
        # a record that would lose AD structures here goes whole into the next packet, if there is one
        full = _ble_record(left[0], BLE_PT_LEN - BLE_HDR_LEN)
        if rec is not None and (rec == full or len(pkts) + 1 >= max_packets or not cur):
            cur += rec
            left.pop(0)
            continue
        pkts.append(cur)
        cur = bytearray()
    if cur or not pkts:
        if len(pkts) < max_packets:
            pkts.append(cur)
    total = len(pkts)
    out = []
    for k, body in enumerate(pkts):
        hdr = bytes([TYPE_BLE, k | ((total - 1) << 2) | (0x10 if survey.active else 0) | (0x20 if left else 0),
                     min(survey.seconds, 255), min(survey.devices, 255), min(survey.stable, 255),
                     min(survey.rotating, 255), min(survey.rotating_apple, 255),
                     min(survey.rotating_microsoft, 255)])
        pt = hdr + bytes(body)
        out.append(pt + bytes(BLE_PT_LEN - len(pt)))
    return out


# ---------------------------------------------------------------- Wi-Fi (docs/design/wifi-positioning.md)

@dataclass
class WifiAp:
    bssid: bytes  # 6 bytes as written
    rssi: int
    ssid: str = ""

    @property
    def seedable(self) -> bool:
        """A position service knows only globally administered unicast MACs."""
        return not (self.bssid[0] & 0x03)


def wifi_usable(ap: WifiAp) -> bool:
    s = ap.ssid
    return not (s.endswith("_nomap") or "_optout" in s)


@dataclass
class WifiPacket:
    """Type 0x24, copy j = J_WIFI: one more full BSSID and six tails."""

    ap_count: int = 0
    seed: bytes = b"\0" * 6
    seed_rssi: int = -95
    tails: list[tuple[bytes, int]] = field(default_factory=list)  # (3 bytes, dBm), at most 6

    def encode(self) -> bytes:
        out = bytearray([TYPE_WIFI, min(self.ap_count, 255)]) + bytes(self.seed[:6]) + bytes([rssi_q(self.seed_rssi)])
        for i in range(6):
            if i < len(self.tails):
                t, r = self.tails[i]
                out += bytes(t[-3:]) + bytes([rssi_q(r) | 0x80])
            else:
                out += b"\0\0\0\0"
        assert len(out) == WIFI_PT_LEN
        return bytes(out)

    @classmethod
    def decode(cls, pt: bytes) -> "WifiPacket":
        if len(pt) not in (REPORT_PT_LEN, REPORT_PT_LEN_STEALTH) or pt[0] != TYPE_WIFI:
            raise ValueError("not an LT2 Wi-Fi packet")
        tails = []
        for i in range(6):
            o = 9 + 4 * i
            if pt[o + 3] & 0x80:
                tails.append((bytes(pt[o:o + 3]), q_rssi(pt[o + 3])))
        return cls(ap_count=pt[1], seed=bytes(pt[2:8]), seed_rssi=q_rssi(pt[8]), tails=tails)


def wifi_select(aps: list[WifiAp], rotation: int,
                seed2_in_report: bool = False) -> tuple[WifiFields | None, WifiPacket | None]:
    """What goes into the report (Wi-Fi layout) and the optional Wi-Fi packet. The strongest
    seedable AP is the report's seed; the tail rotates over the next 7 best with `rotation` (the
    report counter), so the app collects the 8 best over a few reports. With two or more tail
    candidates a second tail replaces the repeater, and the two tails take turns over the same list
    (2n and 2n + 1). The packet carries the
    second-best seedable AP in full and the six strongest others as tails."""
    use = sorted((a for a in aps if wifi_usable(a)), key=lambda a: (-a.rssi, a.bssid))
    seeds = [a for a in use if a.seedable]
    if not seeds:
        return None, None
    seed = seeds[0]
    in2 = seeds[1] if seed2_in_report and len(seeds) > 1 else None
    rest = [a for a in use if a is not seed and a is not in2][:7]
    fields = WifiFields(seed=seed.bssid, seed_rssi=seed.rssi, ap_count=min(len(use), 7))
    if in2 is not None:
        fields.seed2, fields.seed2_rssi = in2.bssid, in2.rssi
    if len(rest) >= 2:
        t, t2 = rest[(2 * rotation) % len(rest)], rest[(2 * rotation + 1) % len(rest)]
        fields.tail, fields.tail_rssi = t.bssid[3:], t.rssi
        fields.tail2, fields.tail2_rssi = t2.bssid[3:], t2.rssi
    elif rest:
        fields.tail, fields.tail_rssi = rest[0].bssid[3:], rest[0].rssi
    pkt = None
    if len(use) > 1:
        seed2 = seeds[1] if len(seeds) > 1 else None
        others = [a for a in use if a is not seed and a is not seed2][:6]
        pkt = WifiPacket(ap_count=len(use), seed=seed2.bssid if seed2 else b"\0" * 6,
                         seed_rssi=seed2.rssi if seed2 else -95, tails=[(a.bssid[3:], a.rssi) for a in others])
    return fields, pkt


# ---------------------------------------------------------------- BLE resolvable private address (Core spec Vol 3 H 2.2.2)

def rpa_matches(irk: bytes, addr: bytes) -> bool:
    """irk and addr as written (most significant byte first). An RPA is prand(3) || hash(3) with the
    top bits of prand 0b01."""
    if len(addr) != 6 or (addr[0] & 0xC0) != 0x40:
        return False
    e = Cipher(algorithms.AES(irk), modes.ECB()).encryptor()
    r = e.update(bytes(13) + addr[:3]) + e.finalize()
    return hmac.compare_digest(r[13:16], addr[3:6])


# ---------------------------------------------------------------- command (PROTOCOL.md 3)

@dataclass
class Command:
    seq: int
    op: Op
    args: bytes = b""

    def encode(self) -> bytes:
        return bytes([TYPE_COMMAND, self.seq & 0xFF, int(self.op)]) + self.args[:14].ljust(14, b"\0")

    @classmethod
    def decode(cls, pt: bytes) -> "Command":
        if len(pt) != CMD_PT_LEN or pt[0] != TYPE_COMMAND:
            raise ValueError("not an LT2 command")
        return cls(pt[1], Op(pt[2]), pt[3:])

    @classmethod
    def set_mode(cls, seq: int, mode: Mode, interval_s: int = 0, duration_min: int = 0) -> "Command":
        return cls(seq, Op.SET_MODE, struct.pack("<BHH", int(mode), interval_s, duration_min))

    @classmethod
    def set_idle(cls, seq: int, interval_min: int, listen_s: int = 0, check_s: int = 0,
                 report_every: int = 0) -> "Command":
        """0 keeps a value. check_s (0.7, offset 7): listening at checks 1..180 s (0.7.2); CHECK_OFF turns it off.
        report_every (0.7.3, offset 8): report at every n-th wake-up, 1..24 (1 = every wake-up)."""
        return cls(seq, Op.SET_IDLE, struct.pack("<HHBB", interval_min, listen_s, check_s, report_every))

    @classmethod
    def buzz(cls, seq: int, seconds: int) -> "Command":
        return cls(seq, Op.BUZZ, bytes([max(1, min(60, seconds))]))

    @classmethod
    def ble_scan(cls, seq: int, seconds: int = 0, active: bool = False, packets: int = 0) -> "Command":
        """Scan Bluetooth now (0 = 8 s, at most 20) and send up to `packets` BLE packets (0 = 2, at most 4)."""
        return cls(seq, Op.BLE_SCAN, bytes([max(0, min(20, seconds)), 1 if active else 0,
                                            max(0, min(BLE_MAX_PACKETS, packets))]))


    @classmethod
    def ble_console(cls, seq: int, window_s: int = 0) -> "Command":
        """0.9.2: open the Bluetooth console window at once; window in units of 10 s (0 = 180 s,
        the tag clamps to 30..600 s). Ends before offset 8, so it has a Bluetooth advert form (3.1)."""
        return cls(seq, Op.BLE_CONSOLE, bytes([max(0, min(255, (window_s + 9) // 10))]))


def seal_command(cmd_e: bytes, n: int, cmd: Command, k: int = 0) -> bytes:
    """A command for report n: k = 0 the window right after it, k >= 1 check k after it (0.7)."""
    return _seal(_cmd_kd(cmd_e, n, k), command_tag(cmd_e, n, k), cmd.encode())


def open_command(cmd_e: bytes, n: int, p: bytes, k: int = 0) -> Command | None:
    if len(p) != CMD_P_LEN or p[:TAG_LEN] != command_tag(cmd_e, n, k):
        return None
    pt = _open(_cmd_kd(cmd_e, n, k), p)
    if pt is None:
        return None
    try:
        return Command.decode(pt)
    except ValueError:
        return None


# ---------------------------------------------------------------- command over Bluetooth (PROTOCOL.md 3.1)

BLE_CMD_COMPANY = 0xFFFF  # manufacturer data company id (no SIG id assigned)
BLE_CMD_LEN = 21          # after the company id: type, tag 4, ciphertext 8, mac 8
BLE_CMD_CT_LEN = 8        # plaintext bytes 1..8 (seq, opcode, args 0..5) go on air
BLE_K_NEXT = 0xFFFF       # "the next wake-up after report n" when it is not a check


def ble_command(cmd_e: bytes, n: int, cmd: Command, k: int) -> bytes | None:
    """The 21 B advert payload of a command sealed for (n, k), or None when the plaintext has
    something after offset 8 (it would not fit; the mesh carries it). The bytes are those of the
    full sealed command: the constant type byte and the zero tail are left out."""
    pt = cmd.encode()
    if any(pt[1 + BLE_CMD_CT_LEN:]):
        return None
    p = seal_command(cmd_e, n, cmd, k)
    return bytes([TYPE_COMMAND]) + p[:TAG_LEN] + p[TAG_LEN + 1:TAG_LEN + 1 + BLE_CMD_CT_LEN] + p[-MAC_LEN:]


def ble_command_expand(cmd_e: bytes, n: int, k: int, adv: bytes) -> bytes | None:
    """The full 29 B command from the advert payload: the left-out ciphertext bytes are the
    keystream over the type byte and the zero tail. Not verified here (open_command does it)."""
    if len(adv) != BLE_CMD_LEN or adv[0] != TYPE_COMMAND:
        return None
    tag, ct, mac = adv[1:1 + TAG_LEN], adv[1 + TAG_LEN:1 + TAG_LEN + BLE_CMD_CT_LEN], adv[-MAC_LEN:]
    ks = _ctr(_cmd_kd(cmd_e, n, k)[:16], mac, bytes(CMD_PT_LEN))
    return tag + bytes([TYPE_COMMAND ^ ks[0]]) + ct + ks[1 + BLE_CMD_CT_LEN:] + mac


def open_ble_command(cmd_e: bytes, n: int, adv: bytes, k: int) -> Command | None:
    """A command advert for (n, k): the tag check first (cheap), then exactly open_command."""
    if len(adv) != BLE_CMD_LEN or adv[0] != TYPE_COMMAND or adv[1:1 + TAG_LEN] != command_tag(cmd_e, n, k):
        return None
    p = ble_command_expand(cmd_e, n, k, adv)
    return open_command(cmd_e, n, p, k) if p else None


def ble_command_ad(adv: bytes) -> bytes:
    """The AD structure on air: manufacturer specific data, company 0xFFFF, then the payload (25 B)."""
    return ad(0xFF, struct.pack("<H", BLE_CMD_COMPANY) + adv)


def ble_command_find(ad_bytes: bytes) -> bytes | None:
    """The command payload in an advert's AD bytes, or None."""
    i = 0
    while i < len(ad_bytes):
        ln = ad_bytes[i]
        if ln == 0 or i + 1 + ln > len(ad_bytes):
            return None
        t, d = ad_bytes[i + 1], ad_bytes[i + 2:i + 1 + ln]
        if (t == 0xFF and len(d) == 2 + BLE_CMD_LEN and struct.unpack_from("<H", d)[0] == BLE_CMD_COMPANY
                and d[2] == TYPE_COMMAND):
            return d[2:]
        i += 1 + ln
    return None


# ---------------------------------------------------------------- console over Bluetooth (PROTOCOL.md 6.2)

BCON_ADV_TYPE = 0x25      # first byte of the console advert's manufacturer data (after company 0xFFFF)
BCON_ADV_LEN = 14         # type, left, salt 6, tag 6
BCON_VER = 0x01
BCON_HELLO, BCON_AUTH, BCON_READY, BCON_DATA = 0x01, 0x02, 0x03, 0x10
BCON_NONCE_LEN = 16
BCON_PROOF_LEN = 16
BCON_MAC_LEN = 8          # plain seal: commands, history records, BLE and Wi-Fi packets
RMAC_LEN = 6         # report v2 (j = 0, 1, on air)
COMMIT_LEN = 3       # report v2: commitment c to the mailbox key pk_n, at P[4:7]
COMMIT_OFF = TAG_LEN
BCON_DATA_MAX = 235       # console bytes in one DATA frame at ATT MTU 247
BCON_A2T, BCON_T2A = 0, 1  # direction: app to tag, tag to app
BCON_WINDOW_S = 180       # after a boot, and a BLE_CONSOLE command with argument 0
BCON_CMD_MIN_S, BCON_CMD_MAX_S = 30, 600


def bcon_cmd_window_s(arg: int) -> int:
    """lt_bcon_cmd_window_s: the window a BLE_CONSOLE command opens (argument in 10 s, 0 = 180 s)."""
    s = arg * 10 if arg else BCON_WINDOW_S
    return max(BCON_CMD_MIN_S, min(BCON_CMD_MAX_S, s))


def bcon_left(left_s: int) -> int:
    """`left` byte of the advert: seconds until the window closes / 2, rounded up; 255 = more than 508 s."""
    return min(255, (max(0, left_s) + 1) // 2)


def bcon_adv_tag(usb_key: bytes, salt: bytes) -> bytes:
    return hmac_sha256(usb_key, b"LT2-bcon-adv" + salt)[:6]


def bcon_adv(usb_key: bytes, salt: bytes, left_s: int) -> bytes:
    """The 14 B after company id 0xFFFF in the console advert."""
    assert len(salt) == 6
    return bytes([BCON_ADV_TYPE, bcon_left(left_s)]) + salt + bcon_adv_tag(usb_key, salt)


def bcon_adv_match(usb_key: bytes, payload: bytes) -> bool:
    return (len(payload) == BCON_ADV_LEN and payload[0] == BCON_ADV_TYPE
            and hmac.compare_digest(payload[8:14], bcon_adv_tag(usb_key, payload[2:8])))


def bcon_adv_ad(payload: bytes) -> bytes:
    """The AD structure on air: manufacturer specific data, company 0xFFFF, then the payload (18 B)."""
    return ad(0xFF, struct.pack("<H", BLE_CMD_COMPANY) + payload)


# 0.9.5: console wake advert (PROTOCOL.md 3.2). The phone advertises it; the tag opens its console
# window. Keyed with usb_key only, so it works whatever report counter n the tag has reached.
BCON_WAKE_TYPE = 0x26
BCON_WAKE_LEN = 13        # type, salt 6, tag 6
BCON_WAKE_SALT_LEN = 6


def bcon_wake_tag(usb_key: bytes, salt: bytes) -> bytes:
    return hmac_sha256(usb_key, b"LT2-bcon-wake" + salt)[:6]


def bcon_wake(usb_key: bytes, salt: bytes) -> bytes:
    """The 13 B after company id 0xFFFF in the phone's console wake advert: 0x26 || salt || tag."""
    assert len(salt) == BCON_WAKE_SALT_LEN
    return bytes([BCON_WAKE_TYPE]) + salt + bcon_wake_tag(usb_key, salt)


def bcon_wake_match(usb_key: bytes, payload: bytes) -> bool:
    return (len(payload) == BCON_WAKE_LEN and payload[0] == BCON_WAKE_TYPE
            and hmac.compare_digest(payload[7:13], bcon_wake_tag(usb_key, payload[1:7])))


def bcon_wake_ad(payload: bytes) -> bytes:
    """The AD structure on air: manufacturer specific data, company 0xFFFF, then the payload (17 B)."""
    return ad(0xFF, struct.pack("<H", BLE_CMD_COMPANY) + payload)


def bcon_wake_find(ad_bytes: bytes) -> bytes | None:
    """The wake payload in an advert's AD bytes, or None (lt_bcon_wake_find)."""
    i = 0
    while i < len(ad_bytes):
        ln = ad_bytes[i]
        if ln == 0 or i + 1 + ln > len(ad_bytes):
            return None
        t, d = ad_bytes[i + 1], ad_bytes[i + 2:i + 1 + ln]
        if (t == 0xFF and len(d) == 2 + BCON_WAKE_LEN and struct.unpack_from("<H", d)[0] == BLE_CMD_COMPANY
                and d[2] == BCON_WAKE_TYPE):
            return d[2:]
        i += 1 + ln
    return None


def vector_bcon_wake(usb_key: bytes, other_key: bytes) -> dict:
    salts = [bytes.fromhex(x) for x in ("a1b2c3d4e5f6", "000000000000", "ffffffffffff", "5a0f3c96e1d2")]
    valid = [{"salt": s.hex(), "tag": bcon_wake_tag(usb_key, s).hex(), "payload": bcon_wake(usb_key, s).hex(),
              "ad": bcon_wake_ad(bcon_wake(usb_key, s)).hex()} for s in salts]
    p = bcon_wake(usb_key, salts[0])
    flip = lambda b, i: b[:i] + bytes([b[i] ^ 1]) + b[i + 1:]  # noqa: E731
    invalid = [
        {"why": "other key", "payload": bcon_wake(other_key, salts[0]).hex()},
        {"why": "salt changed", "payload": flip(p, 1).hex()},
        {"why": "tag changed", "payload": flip(p, 12).hex()},
        {"why": "type 0x25", "payload": (bytes([BCON_ADV_TYPE]) + p[1:]).hex()},
        {"why": "short", "payload": p[:12].hex()},
        {"why": "long", "payload": (p + b"\x00").hex()},
        {"why": "console advert tag", "payload": (p[:7] + bcon_adv_tag(usb_key, salts[0])).hex()},
    ]
    w = bcon_wake_ad(p)
    find = [
        {"ad": w.hex(), "payload": p.hex()},
        {"ad": "020106" + w.hex(), "payload": p.hex()},
        {"ad": w.hex() + "020a00", "payload": p.hex()},
        {"ad": "020106" + w[:-1].hex(), "payload": None},                         # truncated
        {"ad": "10ff4c00" + p.hex(), "payload": None},                           # Apple company id
        {"ad": "11ffffff" + p.hex() + "00", "payload": None},                     # 14 B payload
        {"ad": "10ffffff22" + p[1:].hex(), "payload": None},                      # type 0x22
        {"ad": "00" + w.hex(), "payload": None},                                  # zero length stops the walk
    ]
    for f in find:
        got = bcon_wake_find(bytes.fromhex(f["ad"]))
        assert (got.hex() if got else None) == f["payload"], f
    return {"label": "LT2-bcon-wake", "company": BLE_CMD_COMPANY, "type": BCON_WAKE_TYPE,
            "valid": valid, "invalid": invalid, "find": find}


def bcon_proof(usb_key: bytes, who: str, tag_nonce: bytes, app_nonce: bytes) -> bytes:
    """who = "a" (the app's proof in AUTH) or "t" (the tag's proof in READY)."""
    assert who in ("a", "t")
    return hmac_sha256(usb_key, b"LT2-bcon-" + who.encode() + tag_nonce + app_nonce)[:BCON_PROOF_LEN]


class BconSession:
    """Session keys and frame counters of one console connection. d: BCON_A2T or BCON_T2A."""

    def __init__(self, usb_key: bytes, tag_nonce: bytes, app_nonce: bytes):
        ks = hmac_sha256(usb_key, b"LT2-bcon-k" + tag_nonce + app_nonce)
        self.kd = [hmac_sha256(ks, b"LT2-bcon-a2t"), hmac_sha256(ks, b"LT2-bcon-t2a")]
        self.ctr = [0, 0]

    def _mac(self, d: int, c: int, ct: bytes) -> bytes:
        return hmac_sha256(self.kd[d][16:32], _u32(c) + ct)[:BCON_MAC_LEN]

    def seal(self, d: int, pt: bytes) -> bytes:
        """A DATA frame: 0x10 || ct || mac. Advances the counter of that direction."""
        assert 1 <= len(pt)
        c = self.ctr[d]
        ct = _ctr(self.kd[d][:16], _u32(c) + bytes(4), pt)
        self.ctr[d] = (c + 1) & 0xFFFFFFFF
        return bytes([BCON_DATA]) + ct + self._mac(d, c, ct)

    def open(self, d: int, frame: bytes) -> bytes | None:
        """The plaintext of the next DATA frame of that direction, or None (the connection ends)."""
        if len(frame) < 2 + BCON_MAC_LEN or frame[0] != BCON_DATA:
            return None
        c, ct, mac = self.ctr[d], frame[1:-BCON_MAC_LEN], frame[-BCON_MAC_LEN:]
        if not hmac.compare_digest(mac, self._mac(d, c, ct)):
            return None
        self.ctr[d] = (c + 1) & 0xFFFFFFFF
        return _ctr(self.kd[d][:16], _u32(c) + bytes(4), ct)


def bcon_window_ok(locked: bool, off: bool, tx_allowed: bool, resets: int) -> bool:
    """Does the console window open at boot? Paired, not switched off, above the transmit floor,
    and not in a reset loop (more than 3 resets in a row; deliberate resets set the count to 1,
    policy.boot_resets_next)."""
    return locked and not off and tx_allowed and resets <= 3


def vector_ble_console(usb_key: bytes) -> dict:
    salt = bytes.fromhex("a1b2c3d4e5f6")
    tn, an = bytes(range(0x40, 0x50)), bytes(range(0x80, 0x90))
    s = BconSession(usb_key, tn, an)
    ks = hmac_sha256(usb_key, b"LT2-bcon-k" + tn + an)
    frames = []
    for d in (BCON_A2T, BCON_T2A):
        for c in (0, 1, 0xFFFFFFFF):
            for ln in (1, 16, 17, 235):
                pt = bytes((i * 7 + c + d) & 0xFF for i in range(ln))
                s.ctr[d] = c
                frames.append({"dir": d, "c": c, "pt": pt.hex(), "frame": s.seal(d, pt).hex()})
    return {
        "salt": salt.hex(), "adv_tag": bcon_adv_tag(usb_key, salt).hex(),
        "adv": [{"left_s": l, "payload": bcon_adv(usb_key, salt, l).hex()} for l in (180, 1, 0, 508, 509, 600)],
        "adv_ad": bcon_adv_ad(bcon_adv(usb_key, salt, 180)).hex(),
        "tag_nonce": tn.hex(), "app_nonce": an.hex(),
        "proof_a": bcon_proof(usb_key, "a", tn, an).hex(), "proof_t": bcon_proof(usb_key, "t", tn, an).hex(),
        "ks": ks.hex(), "kd_a": s.kd[0].hex(), "kd_t": s.kd[1].hex(),
        "frames": frames,
        "window_ok": [[int(lk), int(off), int(tx), r, int(bcon_window_ok(lk, off, tx, r))]
                      for lk in (False, True) for off in (False, True) for tx in (False, True) for r in (1, 3, 4)],
    }


# ---------------------------------------------------------------- receiver window (PROTOCOL.md 2)

class ReportWindow:
    """x tag -> (n, j) over last_n - 256 ... last_n + 2048; seen counters are never reopened."""

    def __init__(self, keys: Keys, last_n: int = -1, seen: set[int] | None = None):
        self.keys = keys
        self.last_n = last_n
        self.seen = set(seen or ())
        self._build()

    def _build(self) -> None:
        lo = 0 if self.last_n < 0 else max(0, self.last_n - WINDOW_BACK)
        hi = FIRST_SYNC if self.last_n < 0 else self.last_n + 1 + WINDOW_AHEAD
        self.table: dict[bytes, tuple[int, int]] = {}
        cache: dict[int, bytes] = {}
        for n in range(lo, hi):
            if n in self.seen:
                continue
            e = epoch(n)
            if e not in cache:
                cache[e] = self.keys.view(e)
            for j in J_ON_AIR:
                self.table[report_tag(cache[e], n, j)] = (n, j)
        # BLE and Wi-Fi packets follow a report within seconds: only near the newest report
        self.extra: dict[bytes, tuple[int, int]] = {}
        base = max(self.last_n, 0)
        for n in range(max(0, base - EXTRA_BACK), base + EXTRA_AHEAD + 1):
            e = epoch(n)
            if e not in cache:
                cache[e] = self.keys.view(e)
            for j in J_EXTRA:
                self.extra[report_tag(cache[e], n, j)] = (n, j)

    def tags(self, count: int | None = None) -> list[bytes]:
        """Tags of the next counters ahead (for a relay filter)."""
        items = sorted(self.table.items(), key=lambda kv: kv[1])
        start = max(self.last_n + 1, 0)
        ahead = [t for t, (n, _) in items if n >= start]
        return ahead[:count] if count else ahead

    def open(self, p: bytes) -> tuple[int, int, Report] | None:
        hit = self.table.get(bytes(p[:TAG_LEN]))
        if hit is None:
            return None
        n, j = hit
        r = open_report(self.keys.view(epoch(n)), n, j, p)
        if r is None:
            return None
        self.seen.add(n)
        if n > self.last_n:
            self.last_n = n
            self.seen = {s for s in self.seen if s >= self.last_n - WINDOW_BACK}
            self._build()
        else:
            for jj in J_ON_AIR:
                self.table.pop(report_tag(self.keys.view(epoch(n)), n, jj), None)
        return n, j, r

    def open_extra(self, p: bytes) -> "tuple[int, int, BlePacket | WifiPacket] | None":
        """A BLE (j = 5..8) or Wi-Fi (j = 4) packet that belongs to report n."""
        hit = self.extra.get(bytes(p[:TAG_LEN]))
        if hit is None:
            return None
        n, j = hit
        x = open_extra(self.keys.view(epoch(n)), n, j, p)
        return None if x is None else (n, j, x)


# ---------------------------------------------------------------- MeshCore channel (PROTOCOL.md 2 Doprava)

def channel_key(name: str = CHANNEL_NAME) -> bytes:
    return hashlib.sha256(name.encode()).digest()[:16]


def _ecb(key: bytes, data: bytes, decrypt: bool = False) -> bytes:
    c = Cipher(algorithms.AES(key), modes.ECB())
    op = c.decryptor() if decrypt else c.encryptor()
    return op.update(data) + op.finalize()


def channel_wrap(inner: bytes, key: bytes | None = None, data_type: int = CHANNEL_DATA_TYPE) -> bytes:
    """GRP_DATA payload carrying `inner` on a hashtag channel (MeshCore channel datagram)."""
    key = key or channel_key()
    pt = struct.pack("<HB", data_type, len(inner)) + inner
    pt += b"\0" * (-len(pt) % 16)
    ct = _ecb(key, pt)
    mac = hmac_sha256(key + b"\0" * 16, ct)[:2]
    return bytes([hashlib.sha256(key).digest()[0]]) + mac + ct


def channel_unwrap(payload: bytes, key: bytes | None = None, data_type: int = CHANNEL_DATA_TYPE) -> bytes | None:
    key = key or channel_key()
    if len(payload) < 19 or (len(payload) - 3) % 16 or payload[0] != hashlib.sha256(key).digest()[0]:
        return None
    ct = payload[3:]
    if not hmac.compare_digest(hmac_sha256(key + b"\0" * 16, ct)[:2], payload[1:3]):
        return None
    pt = _ecb(key, ct, decrypt=True)
    dt, ln = struct.unpack_from("<HB", pt)
    if dt != data_type or 3 + ln > len(pt):
        return None
    return pt[3:3 + ln]


# ---------------------------------------------------------------- vectors

def report_time(created_at: int, tags) -> tuple[int, int | None]:
    """When a kind 4243 report was heard (PROTOCOL.md 4): (earliest, latest) in unix seconds, latest
    None for an exact report. With ["approx", "<earliest>"] the time is only known to lie between the
    tag's value and created_at (the latest possible time). A missing or malformed value gives an empty
    range at created_at that still counts as approximate. The order of the two values does not matter:
    bridge 0.3.0 put them the other way round."""
    tag = next((t for t in tags if isinstance(t, list) and t and t[0] == "approx"), None)
    if tag is None:
        return created_at, None
    v = tag[1] if len(tag) > 1 else ""
    other = int(v) if isinstance(v, str) and v.isdigit() and len(v) <= 12 else created_at
    return min(other, created_at), max(other, created_at)


def vector_report_time() -> list[dict]:
    """Shared cases for report_time (apps and bridges read the same file)."""
    t = 1_800_000_000
    cases = [
        ("exact", []),
        ("approx", [["approx", str(t - 3600)]]),
        ("approx equal to created_at", [["approx", str(t)]]),
        ("bridge 0.3.0 order", [["approx", str(t + 600)]]),
        ("malformed", [["approx", "x"]]),
        ("negative", [["approx", "-5"]]),
        ("no value", [["approx"]]),
        ("other tags first", [["y", "00" * 16], ["expiration", str(t + 14 * 86400)], ["approx", str(t - 60)]]),
    ]
    out = []
    for name, tags in cases:
        lo, hi = report_time(t, tags)
        out.append({"name": name, "created_at": t, "tags": tags, "from": lo, "until": hi})
    return out


def vector_boot_reports(keys: "Keys") -> list[dict]:
    """0.9.4: BOOT bit (byte 2 bit 2) and the reset information in byte 14 (PROTOCOL.md 2)."""
    aps = [WifiAp(bytes.fromhex("0024a5112233"), -58, "lt-ap-1"), WifiAp(bytes.fromhex("6c3b6b44aa21"), -71, "lt-ap-2"),
           WifiAp(bytes.fromhex("e4f4c6778899"), -80, "lt-ap-3")]
    seed2, _ = wifi_select(aps, 310, seed2_in_report=True)
    cases = [
        # no fix, a last fix: byte 14 = brownout | 4 << 3
        (306, 0, Report(has_fix=False, fix_time=1763400000, lat=48.701000, lon=21.251000, hacc_m=8, battery_pct=21,
                        mode=Mode.LOST, boot=True, boot_cause="brownout", boot_resets=4, nb_total=1,
                        neighbours=[(bytes.fromhex("a5e1"), 5.5)], next_in_s=1800)),
        # a fix: the bit only, byte 14 stays the accuracy
        (307, 0, Report(has_fix=True, fix_time=1763403600, lat=48.702000, lon=21.252000, hacc_m=6, battery_pct=93,
                        boot=True, nb_total=0, next_in_s=3600)),
        # never had a fix, history record (j = 2): power-on, 1
        (308, 2, Report(has_fix=False, fix_time=0, battery_pct=100, boot=True, boot_cause="power", boot_resets=1,
                        nb_total=2, neighbours=[(bytes.fromhex("b7d2"), 1.0)], next_in_s=30)),
        # resets capped at 31, crash, TRACK (mode 1 | BOOT: a decoder with the old 3-bit mask reads 5)
        (309, 1, Report(has_fix=False, fix_time=1763400000, lat=48.701000, lon=21.251000, battery_pct=40,
                        mode=Mode.TRACK, boot=True, boot_cause="crash", boot_resets=40, next_in_s=120)),
        # Wi-Fi seed2 layout (no fix time): byte 14 is the second BSSID's RSSI, the bit only
        (310, 0, Report(has_fix=False, fix_time=0, battery_pct=77, boot=True, boot_cause="pin", boot_resets=1,
                        nb_total=1, sync=True, next_in_s=3600, wifi=seed2)),
    ]
    out = []
    for n, j, r in cases:
        d = Report.decode(r.encode())
        out.append({"n": n, "j": j, "pt": r.encode().hex(),
                    "pk": mailbox_pk(keys.cmd(epoch(n)), n).hex(),
                    "p": seal_report_keys(keys, n, j, r).hex(),
                    "mode": int(d.mode), "has_fix": d.has_fix, "boot": d.boot, "boot_cause": d.boot_cause,
                    "boot_resets": d.boot_resets, "hacc_m": None if d.hacc_m == 255 else d.hacc_m})
    return out


def vectors() -> dict:
    k0 = bytes(range(32))
    keys = Keys(k0)
    out: dict = {
        "k0": k0.hex(), "id": keys.id.hex(), "usb_key": keys.usb_key.hex(), "dm_sk": keys.dm_sk.hex(),
        "view0": keys.view(0).hex(), "view2": keys.view(2).hex(), "cmd0": keys.cmd(0).hex(), "cmd2": keys.cmd(2).hex(),
        "usb_challenge": bytes(range(16)).hex(), "usb_response": usb_auth(keys.usb_key, bytes(range(16))).hex(),
        "reports": [], "commands": [],
    }
    reports = [
        (5, 0, Report(has_fix=True, fix_time=1763200000, lat=48.700000, lon=21.250000, hacc_m=7, battery_pct=88,
                      mode=Mode.IDLE, cmd_ack=True, cmd_seq=3,
                      neighbours=[(bytes.fromhex("a5e1"), 11.0), (bytes.fromhex("b7d2"), 7.5)], nb_total=2)),
        (130, 1, Report(has_fix=False, fix_time=1763100000, lat=-33.8688, lon=-151.2093, battery_pct=9,
                        mode=Mode.LOST, cmd_seq=15, low_batt=True, nb_discover=True, nb_total=7,
                        neighbours=[(bytes.fromhex("a5e1"), 11.25), (bytes.fromhex("b7d2"), 6.0),
                                    (bytes.fromhex("c3f4"), -1.5), (bytes.fromhex("d4a6"), -5.75),
                                    (bytes.fromhex("e8b9"), -7.0)])),
        (64, 0, Report(has_fix=False, battery_pct=None)),
    ]
    for n, j, r in reports:
        ve = keys.view(epoch(n))
        out["reports"].append({
            "n": n, "j": j, "tag": report_tag(ve, n, j).hex(), "pt": r.encode().hex(),
            "pk": mailbox_pk(keys.cmd(epoch(n)), n).hex(),
            "p": seal_report_keys(keys, n, j, r).hex(), "p_stealth": seal_report_keys(keys, n, j, r, stealth=True).hex(),
            "channel_payload": channel_wrap(seal_report_keys(keys, n, j, r)).hex(),
        })
    # 0.6: quiet, next_in, energy, BLE summary, Wi-Fi layout (bytes 31, 32 were 0 before)
    reports += [
        (200, 0, Report(has_fix=True, fix_time=1763300000, lat=48.700500, lon=21.250000, battery_pct=71,
                        quiet=True, next_in_s=86400, energy_mah=345, nb_total=1,
                        neighbours=[(bytes.fromhex("a5e1"), 9.5)])),
        (201, 0, Report(has_fix=False, fix_time=1763300000, lat=48.700500, lon=21.250000, battery_pct=70,
                        quiet=True, quiet_owner=True, next_in_s=8 * 3600, energy_mah=0, nb_total=6,
                        neighbours=[(bytes.fromhex("a5e1"), 8.0), (bytes.fromhex("b7d2"), 2.25),
                                    (bytes.fromhex("c3f4"), -3.0), (bytes.fromhex("d4a6"), -6.5)],
                        ble=BleSummary(devices=17, stable=5, stable_rssi=-61))),
        (202, 1, Report(has_fix=False, battery_pct=64, mode=Mode.LOST, cmd_ack=True, cmd_seq=4, nb_total=3,
                        neighbours=[(bytes.fromhex("b7d2"), -2.0)], next_in_s=120, energy_mah=2600,
                        wifi=WifiFields(seed=bytes.fromhex("0024a5112233"), seed_rssi=-58, ap_count=9,
                                        tail=bytes.fromhex("44aa21"), tail_rssi=-71))),
    ]
    for n, j, r in reports[3:]:
        ve = keys.view(epoch(n))
        out["reports"].append({
            "n": n, "j": j, "tag": report_tag(ve, n, j).hex(), "pt": r.encode().hex(),
            "pk": mailbox_pk(keys.cmd(epoch(n)), n).hex(),
            "p": seal_report_keys(keys, n, j, r).hex(), "p_stealth": seal_report_keys(keys, n, j, r, stealth=True).hex(),
            "channel_payload": channel_wrap(seal_report_keys(keys, n, j, r)).hex(),
        })
    # 0.7: flags bit 7 (CHECKS then, SYNC since 0.10.0), parked (byte 2 bit 3)
    reports += [
        (203, 0, Report(has_fix=True, fix_time=1763400000, lat=48.715000, lon=21.265000, hacc_m=9, battery_pct=66,
                        quiet=True, quiet_parked=True, sync=True, next_in_s=6 * 3600, energy_mah=410, nb_total=2,
                        neighbours=[(bytes.fromhex("a5e1"), 4.5), (bytes.fromhex("b7d2"), -1.0)])),
        (204, 0, Report(has_fix=False, fix_time=1763400000, lat=48.715000, lon=21.265000, battery_pct=65,
                        quiet=True, quiet_owner=True, sync=True, next_in_s=8 * 3600, energy_mah=420, cmd_ack=True,
                        cmd_seq=6, nb_total=1, neighbours=[(bytes.fromhex("a5e1"), 3.0)])),
        (205, 0, Report(has_fix=True, fix_time=1763410000, lat=48.700500, lon=21.250000, battery_pct=65, sync=True,
                        next_in_s=3600, energy_mah=421, nb_total=0)),
    ]
    for n, j, r in reports[6:]:
        ve = keys.view(epoch(n))
        out["reports"].append({
            "n": n, "j": j, "tag": report_tag(ve, n, j).hex(), "pt": r.encode().hex(),
            "pk": mailbox_pk(keys.cmd(epoch(n)), n).hex(),
            "p": seal_report_keys(keys, n, j, r).hex(), "p_stealth": seal_report_keys(keys, n, j, r, stealth=True).hex(),
            "channel_payload": channel_wrap(seal_report_keys(keys, n, j, r)).hex(),
        })
    out["next_in"] = [[sec, next_in_encode(sec), next_in_decode(next_in_encode(sec))]
                      for sec in (0, 1, 29, 30, 44, 45, 120, 3600, 4049, 4051, 5400, 8 * 3600, 86400, 125100,
                                  200000)]
    out["energy"] = [[m, energy_encode(m)] for m in (0, 9.9, 10, 345, 2539, 2540, 99999)]
    # BLE packets (synthetic devices; addresses and names made up)
    devs = [
        BleDevice(addr=bytes.fromhex("0026ab123456"), kind=ADDR_PUBLIC, rssi=-61, heard=76, connectable=True,
                  scan_rsp=True, ad=ad(0x09, b"Printer 0001") + ad(0xFF, bytes.fromhex("40006c02"))
                  + ad(0x07, bytes.fromhex("59c14a4aed2b73b5594ef44e00002a80"))),
        BleDevice(addr=bytes.fromhex("dc758acd0001"), kind=ADDR_STATIC, rssi=-78, heard=14, connectable=True,
                  scan_rsp=True, anchor=True, ad=ad(0x09, b"Bike Trainer 01") + ad(0x03, bytes.fromhex("18182618"))
                  + ad(0x16, bytes.fromhex("2618012000"))),
        BleDevice(addr=bytes.fromhex("54d2723b0002"), kind=ADDR_PUBLIC, rssi=-85, heard=3, connectable=True,
                  ad=ad(0x09, b"Lock_00000002") + ad(0xFF, bytes.fromhex("4c000215a92ee200550111e4916c0800200c9a66"))),
        BleDevice(addr=bytes.fromhex("5f5e9b7af7e1"), kind=ADDR_RPA, rssi=-40, heard=27,
                  ad=ad(0xFF, bytes.fromhex("4c0009081302c0a800011b581608"))),
        BleDevice(addr=bytes.fromhex("6b87cfef1f96"), kind=ADDR_RPA, rssi=-44, heard=41, connectable=True,
                  scan_rsp=True, ad=ad(0x16, bytes.fromhex("f1fc04586747b878"))
                  + ad(0x07, bytes.fromhex("5c4b3a2c1d8e3f9b5a4c9e4a2d5e7bf4"))),
        BleDevice(addr=bytes.fromhex("3c52898e1a22"), kind=ADDR_NRPA, rssi=-75, heard=21,
                  ad=ad(0xFF, bytes.fromhex("060001092002db045bdc5e8a438b"))),
    ]
    survey = BleSurvey(seconds=10, active=True, devices=17, stable=5, rotating=12, rotating_apple=9,
                       rotating_microsoft=2)
    out["ble"] = []
    for n, maxp in ((201, 4), (201, 1)):
        ve = keys.view(epoch(n))
        pts = ble_pack(survey, devs, maxp)
        out["ble"].append({"n": n, "max_packets": maxp, "packets": [
            {"j": J_BLE + k, "pt": pt.hex(), "p": seal_extra(ve, n, J_BLE + k, pt).hex(),
             "p_stealth": seal_extra(ve, n, J_BLE + k, pt, stealth=True).hex(),
             "channel_payload": channel_wrap(seal_extra(ve, n, J_BLE + k, pt)).hex()}
            for k, pt in enumerate(pts)]})
    out["ble_devices"] = [{"addr": d.addr.hex(), "info": d.info(), "rssi": d.rssi, "heard": d.heard, "ad": d.ad.hex()}
                          for d in devs]
    out["ble_survey"] = [survey.seconds, int(survey.active), survey.devices, survey.stable, survey.rotating,
                         survey.rotating_apple, survey.rotating_microsoft]
    # Wi-Fi: scan -> report fields + packet (synthetic BSSIDs)
    aps = [WifiAp(bytes.fromhex(b), r, ss) for b, r, ss in (
        ("0024a5112233", -58, "home"), ("02aabb000001", -50, "phone hotspot"), ("44d9e7000002", -66, "cafe"),
        ("c8d719000003", -71, "neighbour"), ("00265a000004", -80, "x_nomap"), ("a0f3c1000005", -74, "shop"),
        ("1c7ee5000006", -83, "office"), ("6a00aa000007", -88, ""), ("f8d111000008", -90, "lab"))]
    out["wifi"] = {"aps": [[a.bssid.hex(), a.rssi, a.ssid] for a in aps], "cases": []}
    for n in (300, 301, 302):
        f, pk = wifi_select(aps, n)
        ve = keys.view(epoch(n))
        out["wifi"]["cases"].append({
            "n": n, "seed": f.seed.hex(), "seed_rssi": f.seed_rssi, "ap_count": f.ap_count,
            "tail": f.tail.hex() if f.tail else "", "tail_rssi": f.tail_rssi,
            "tail2": f.tail2.hex() if f.tail2 else "", "tail2_rssi": f.tail2_rssi,
            "pt": pk.encode().hex(), "p": seal_extra(ve, n, J_WIFI, pk.encode()).hex()})
    # Wi-Fi layout of a tracker without any fix time: a second full BSSID in bytes 8..14
    out["wifi_nofix"] = []
    for n in (300, 301):
        f, _ = wifi_select(aps, n, seed2_in_report=True)
        rep = Report(has_fix=False, fix_time=0, hacc_m=255, battery_pct=77, mode=Mode.IDLE, nb_total=2,
                     neighbours=[(bytes.fromhex("a1b2"), 6.25)], sync=True, next_in_s=3600, wifi=f)
        out["wifi_nofix"].append({"n": n, "seed": f.seed.hex(), "seed2": f.seed2.hex(), "seed2_rssi": f.seed2_rssi,
                                  "tail": f.tail.hex() if f.tail else "", "tail_rssi": f.tail_rssi,
                                  "tail2": f.tail2.hex() if f.tail2 else "", "tail2_rssi": f.tail2_rssi,
                                  "pt": rep.encode().hex(),
                                  "pk": mailbox_pk(keys.cmd(epoch(n)), n).hex(),
                                  "p": seal_report_keys(keys, n, 0, rep).hex()})
    # Wi-Fi layout with only two usable APs: one tail, the repeater stays (no second tail)
    few = [aps[0], aps[2]]
    f, _ = wifi_select(few, 303)
    rep = Report(has_fix=False, fix_time=0, hacc_m=255, battery_pct=77, mode=Mode.IDLE, nb_total=2,
                 neighbours=[(bytes.fromhex("a1b2"), 6.25)], sync=True, next_in_s=3600, wifi=f)
    out["wifi_few"] = {"n": 303, "aps": [[a.bssid.hex(), a.rssi, a.ssid] for a in few], "tail": f.tail.hex(),
                       "tail_rssi": f.tail_rssi, "pt": rep.encode().hex(),
                       "pk": mailbox_pk(keys.cmd(epoch(303)), 303).hex(),
                       "p": seal_report_keys(keys, 303, 0, rep).hex()}
    # resolvable private address (Core spec Vol 3 Part H D.7 sample: irk, prand 708194 -> hash 0dfbaa)
    out["rpa"] = {"irk": "ec0234a357c8ad05341010a60a397d9b", "match": "7081940dfbaa", "other": "7081940dfbab"}
    from .wake import vector_scenarios, ZONES_FOR_VECTORS
    from .wake import vector_cfg, vector_checks
    out["wake"] = {"zones": [[z.lat_e7, z.lon_e7, z.r_m] for z in ZONES_FOR_VECTORS],
                   "scenarios": vector_scenarios(), "cfg": vector_cfg()}
    out["checks"] = vector_checks(keys)
    # Wi-Fi zones and the wake decisions of a tracker placed by Wi-Fi (fw-wifi-esp32.md)
    from .wake import vector_wifi_scenarios
    from .wifizone import vector_wifi_zone
    out["wifi_zone"] = vector_wifi_zone()
    out["wake_wifi"] = vector_wifi_scenarios()
    for n, c in [(5, Command.set_mode(1, Mode.LOST, 600, 1440)), (130, Command.buzz(2, 10)),
                 (64, Command.set_idle(3, 30, 60)), (202, Command.ble_scan(5, 10, True, 3)),
                 (203, Command.ble_console(14))]:
        ce = keys.cmd(epoch(n))
        out["commands"].append({"n": n, "tag": command_tag(ce, n).hex(), "pt": c.encode().hex(),
                                "p": seal_command(ce, n, c).hex(),
                                "channel_payload": channel_wrap(seal_command(ce, n, c)).hex()})
    # 0.7: commands at check k >= 1 after report n (k = 0 keeps the derivation above)
    out["check_commands"] = []
    for n, k, c in [(203, 1, Command.set_mode(7, Mode.TRACK, 300, 60)), (203, 3, Command.buzz(8, 5)),
                    (204, 1, Command.set_idle(9, 120, 0, 30)), (64, 3, Command.set_idle(10, 0, 0, CHECK_OFF))]:
        ce = keys.cmd(epoch(n))
        out["check_commands"].append({"n": n, "k": k, "tag": command_tag(ce, n, k).hex(),
                                      "tag_k0": command_tag(ce, n).hex(), "pt": c.encode().hex(),
                                      "p": seal_command(ce, n, c, k).hex(),
                                      "channel_payload": channel_wrap(seal_command(ce, n, c, k)).hex()})
    # 0.7.7: commands over Bluetooth advertising (PROTOCOL.md 3.1); k = BLE_K_NEXT: the next wake-up
    out["ble_commands"] = []
    for n, k, c in [(203, 1, Command.set_mode(7, Mode.TRACK, 300, 60)), (203, 3, Command.buzz(8, 5)),
                    (204, BLE_K_NEXT, Command.set_idle(9, 120, 0, 30, 2)), (64, 2, Command.ble_scan(12, 10, True, 3)),
                    (130, BLE_K_NEXT, Command.set_mode(13, Mode.LOST, 600, 1440)),
                    (203, 2, Command.ble_console(15, 300)), (205, BLE_K_NEXT, Command.ble_console(16))]:
        ce = keys.cmd(epoch(n))
        adv = ble_command(ce, n, c, k)
        out["ble_commands"].append({"n": n, "k": k, "tag": command_tag(ce, n, k).hex(), "pt": c.encode().hex(),
                                    "p": seal_command(ce, n, c, k).hex(), "adv": adv.hex(),
                                    "ad": ble_command_ad(adv).hex()})
    # 0.7.3: report every n-th wake-up, check window by radio preset
    from .wake import vector_check_window, vector_report_every
    out["report_every"] = vector_report_every(keys)
    out["check_window"] = vector_check_window()
    out["set_idle_report_every"] = Command.set_idle(11, 60, 0, 30, 3).encode().hex()
    # 0.8.2: report time from created_at and ["approx", "<earliest>"]
    out["report_time"] = vector_report_time()
    # 0.8.0: console over Bluetooth (PROTOCOL.md 6.2)
    out["ble_console"] = vector_ble_console(keys.usb_key)
    # 0.9.5: console wake advert from the phone, keyed with usb_key (PROTOCOL.md 3.2)
    out["bcon_wake"] = vector_bcon_wake(keys.usb_key, keys.view(0))
    # 0.9.2: BLE_CONSOLE (opcode 0x06), the window from the argument
    out["ble_console_cmd"] = [[a, bcon_cmd_window_s(a)] for a in (0, 1, 2, 3, 4, 18, 30, 60, 61, 255)]
    # 0.9.1: deliberate resets (button, power, cable) are not a reset loop
    from .policy import vector_boot_resets
    out["boot_resets"] = vector_boot_resets()
    # report sealing v2: mailbox keys sk_n / pk_n, history records keep the plain seal (PROTOCOL.md 2, 4)
    out["mailbox_keys"] = [{"n": n, "sk": mailbox_sk(keys.cmd(epoch(n)), n).hex(),
                            "pk": mailbox_pk(keys.cmd(epoch(n)), n).hex()} for n in (0, 5, 63, 64, 130, 205)]
    out["boot_reports"] = vector_boot_reports(keys)
    hist = reports[0][2]
    out["history"] = [{"n": 5, "j": j, "pt": hist.encode().hex(), "p": seal_report_keys(keys, 5, j, hist).hex()}
                      for j in (J_HIST, J_HIST_SILENT)]
    return out


if __name__ == "__main__":
    if sys.argv[1:] == ["vectors"]:
        json.dump(vectors(), sys.stdout, indent=1)
        print()
    else:
        print(__doc__)

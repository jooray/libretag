"""Conformance vectors for Nostr Event Frame profiles (docs/vectors/nostr-frames-v1.json).

Deterministic: fixed keys, fixed NIP-04 IVs, zero aux randomness. Any implementation (firmware,
Kotlin, JS) must encode `event` to exactly `frame` and decode back.
"""
from __future__ import annotations

import hashlib

from . import frag, frames, secp
from .nostr import Event, nip04_content, nip04_encrypt_raw, nip04_key, sign_event

T0 = 1_763_020_800  # 2025-11-13T08:00:00Z


def key(n: int) -> bytes:
    return hashlib.sha256(f"nostr-frames-v1 test key {n}".encode()).digest()


def _iv(n: int) -> bytes:
    return hashlib.sha256(f"nostr-frames-v1 iv {n}".encode()).digest()[:16]


DMX_TEXT = "LibreTag bag: battery 9 %, last GPS 48.70000,21.25000"


def _dm(sk: bytes, rcpt_sk: bytes, text: str, ca: int, ivn: int, expiring: bool = False) -> Event:
    rcpt = secp.pubkey(rcpt_sk)
    ct, iv = nip04_encrypt_raw(nip04_key(sk, rcpt), text, _iv(ivn))
    return sign_event(sk, ca, 4, frames.dm4_tags(rcpt.hex(), ca, expiring), nip04_content(ct, iv))


def generate() -> dict:
    valid, decode_invalid, encode_invalid, fragments = [], [], [], []

    def ok(name, ev, profile=None, registry=None, note=None):
        enc = frames.encode(ev, profile, max_len=None)
        v = {"name": name, "profile": frames.PROFILE_NAMES[enc.profile], "event": ev.to_dict(),
             "frame": enc.frame.hex(), "length": len(enc.frame)}
        if registry:
            v["registry"] = [k.hex() for k in registry]
        if note:
            v["note"] = note
        valid.append(v)
        return enc.frame

    note = ok("note_short", sign_event(key(1), T0, 1, [], "Ahoj z mesh siete!"))
    ok("note_63_bytes_max", sign_event(key(1), T0 + 1, 1, [], "Ľ" * 31 + "!"),
       note="165 bytes, the radio limit; 163 via BLE/local API means 61 bytes")
    ok("note_newline_tab", sign_event(key(1), T0 + 2, 1, [], "a\nb\tc"))
    ok("generic_tags", sign_event(key(2), T0, 30, [["t", "mesh"], ["expiration", "1790000000"], ["d", "007"]], "x"),
       note="item types: char, u32, string (a leading zero stays a string)")
    ok("generic_reaction_hex32", sign_event(key(2), T0, 7, [["e", key(9).hex()]], "+"))
    ok("generic_kind_65535_empty", sign_event(key(2), T0, 65535, [], ""))
    ok("dm4_full_15_bytes", _dm(key(3), key(4), "x" * 15, T0, 1), frames.DM4_FULL,
       note="the largest dm4.full that fits one 165 B frame")
    ok("dm4_ref_63_bytes", _dm(key(3), key(4), "teplota 61.5 C, tlak 1.8 bar, cerpadlo ON, chyba 0, bat 87 %!",
                               T0 + 5, 2), frames.DM4_REF,
       registry=[secp.pubkey(key(3)), secp.pubkey(key(4))], note="decoder resolves 4 B refs via the registry")
    ok("dm4x_ref_low_battery", _dm(key(3), key(4), DMX_TEXT, T0 + 7, 5, expiring=True), frames.DM4X_REF,
       registry=[secp.pubkey(key(3)), secp.pubkey(key(4))],
       note="tracker low-battery DM: same bytes as dm4.ref, the event also has expiration = created_at + 14 d")
    ok("dm4x_full_15_bytes", _dm(key(3), key(4), "x" * 15, T0 + 8, 6, expiring=True), frames.DM4X_FULL)
    ok("generic_inbox_poll", sign_event(key(3), T0 + 20, frames.INBOX_POLL_KIND, [], ""),
       note="a sleeping device says 'listening now'; bridges flush held DMs and never publish it")

    big = _dm(key(3), key(4), "Dlhšia správa pre zariadenie, ktorá sa nezmestí do jedného rámca. " * 2, T0 + 9, 3)
    big_frame = frames.encode(big, frames.DM4_FULL, max_len=None).frame
    fragments.append({"name": "dm4_full_two_fragments_with_parity", "event": big.to_dict(),
                      "frame": big_frame.hex(),
                      "fragments": [p.hex() for p in frag.split(big_frame, 165, parity=True)]})

    def bad(name, frame, error, registry=None):
        v = {"name": name, "frame": frame.hex(), "error": error}
        if registry:
            v["registry"] = [k.hex() for k in registry]
        decode_invalid.append(v)

    bad("empty", b"", "BAD_LENGTH")
    bad("note_truncated", note[:-1], "BAD_LENGTH")
    bad("note_version_1", bytes([0x41]) + note[1:], "BAD_VERSION")
    bad("unknown_profile_0x20", bytes([0x20]) + note[1:], "UNKNOWN_PROFILE")
    flipped = bytearray(note)
    flipped[-1] ^= 1
    bad("note_signature_flipped", bytes(flipped), "SIGNATURE_INVALID")
    bad("note_control_char", note[:38] + b"\x07" + note[39:], "NON_CANONICAL")
    bad("note_invalid_utf8", note[:38] + b"\xc3\x28" + note[40:], "NON_CANONICAL")
    dm_ref = bytes.fromhex(next(v["frame"] for v in valid if v["name"] == "dm4_ref_63_bytes"))
    bad("dm4_ref_unknown_ref", dm_ref, "UNKNOWN_REF")
    bad("dm4_ref_ct_not_block", dm_ref[:-65] + dm_ref[-64:], "BAD_LENGTH",
        registry=[secp.pubkey(key(3)), secp.pubkey(key(4))])
    bad("over_165_not_dm", note + b"x" * 64, "BAD_LENGTH")

    def refuse(name, ev, error, profile=None):
        encode_invalid.append({"name": name, "event": ev.to_dict(), "error": error,
                               **({"profile": frames.PROFILE_NAMES[profile]} if profile is not None else {})})

    refuse("note_bell_char", sign_event(key(1), T0, 1, [], "ding\x07"), "NON_CANONICAL")
    refuse("note_too_long_for_radio", sign_event(key(1), T0, 1, [], "x" * 64), "BAD_LENGTH")
    refuse("generic_16_tags", sign_event(key(2), T0, 7, [["t", str(i)] for i in range(16)], ""), "BAD_LENGTH")
    d = _dm(key(3), key(4), "hi", T0, 4)
    ct, iv = d.content.split("?iv=")
    refuse("dm4_unpadded_base64", sign_event(key(3), T0, 4, d.tags, ct.rstrip("=") + "?iv=" + iv),
           "NON_CANONICAL_BASE64", frames.DM4_FULL)
    refuse("dm4_two_p_tags", sign_event(key(3), T0, 4, d.tags * 2, d.content), "NON_CANONICAL", frames.DM4_FULL)
    dx = _dm(key(3), key(4), "hi", T0, 4, expiring=True)
    refuse("dm4x_other_expiration", sign_event(key(3), T0, 4, [d.tags[0], ["expiration", str(T0 + 3600)]], d.content),
           "NON_CANONICAL", frames.DM4X_REF)
    refuse("dm4x_profile_without_expiration", d, "NON_CANONICAL", frames.DM4X_FULL)
    refuse("dm4_profile_with_expiration", dx, "NON_CANONICAL", frames.DM4_FULL)
    tampered = Event.from_dict(sign_event(key(1), T0, 1, [], "hello").to_dict())
    tampered.sig = tampered.sig[:-2] + ("00" if tampered.sig[-2:] != "00" else "01")
    refuse("tampered_signature", tampered, "SIGNATURE_INVALID")

    return {
        "description": "Conformance vectors for the Nostr Event Frame (docs/nostr-mesh-bridge.md), "
                       "data_type 0xFF4E on #nostr. Generated by `nostrmesh vectors`; do not edit by hand. "
                       "Road profiles: see roadbeacon nips/vectors/road-events-v1.json.",
        "version": 1,
        "limits": {"radio": frames.MAX_RADIO, "ble_companion": frames.MAX_COMPANION_BLE,
                   "fragment_header": frag.HEADER, "max_fragments": frag.MAX_FRAGMENTS},
        "keys": {f"key({n})": key(n).hex() for n in range(1, 5)},
        "valid": valid,
        "fragments": fragments,
        "decode_invalid": decode_invalid,
        "encode_invalid": encode_invalid,
    }


def c_header() -> str:
    """firmware/lt_core/test/nostr_vectors.h, the same vectors for the C implementation."""
    d = generate()
    v = {x["name"]: x for x in d["valid"]}

    def arr(name, b: bytes):
        body = ", ".join(f"0x{x:02x}" for x in b)
        return f"static const uint8_t {name}[{len(b)}] = {{{body}}};\n"

    out = "/* Generated by `nostrmesh vectors --c-header`; do not edit. */\n#pragma once\n#include <stdint.h>\n\n"
    out += f"#define NV_T0 {T0}u\n"
    out += arr("NV_SK3", key(3)) + arr("NV_SK4", key(4))
    out += arr("NV_PK3", secp.pubkey(key(3))) + arr("NV_PK4", secp.pubkey(key(4)))
    for name, ivn in (("dm4_full_15_bytes", 1), ("dm4_ref_63_bytes", 2)):
        e = v[name]["event"]
        tag = "NV_DM_FULL" if "full" in name else "NV_DM_REF"
        text = "x" * 15 if "full" in name else "teplota 61.5 C, tlak 1.8 bar, cerpadlo ON, chyba 0, bat 87 %!"
        out += arr(tag + "_IV", _iv(ivn)) + arr(tag + "_FRAME", bytes.fromhex(v[name]["frame"]))
        out += f'#define {tag}_TEXT "{text}"\n#define {tag}_CREATED_AT {e["created_at"]}u\n'
    e = v["dm4x_ref_low_battery"]["event"]
    out += arr("NV_DMX_REF_IV", _iv(5)) + arr("NV_DMX_REF_FRAME", bytes.fromhex(v["dm4x_ref_low_battery"]["frame"]))
    out += f'#define NV_DMX_REF_TEXT "{DMX_TEXT}"\n#define NV_DMX_REF_CREATED_AT {e["created_at"]}u\n'
    out += f'#define NV_DMX_REF_TAGS_JSON "{__import__("json").dumps(e["tags"], separators=(",", ":")).replace(chr(34), chr(92) + chr(34))}"\n'
    out += f'#define NV_DMX_REF_ID "{e["id"]}"\n'
    e = v["dm4x_full_15_bytes"]["event"]
    out += arr("NV_DMX_FULL_IV", _iv(6)) + arr("NV_DMX_FULL_FRAME", bytes.fromhex(v["dm4x_full_15_bytes"]["frame"]))
    out += f'#define NV_DMX_FULL_TEXT "{"x" * 15}"\n#define NV_DMX_FULL_CREATED_AT {e["created_at"]}u\n'
    e = v["generic_inbox_poll"]["event"]
    out += arr("NV_POLL_FRAME", bytes.fromhex(v["generic_inbox_poll"]["frame"]))
    out += f'#define NV_POLL_CREATED_AT {e["created_at"]}u\n'
    out += f'#define NV_NPUB3 "{__import__("nostrmesh.bech32", fromlist=["x"]).encode("npub", secp.pubkey(key(3)))}"\n'
    return out


AIRTIME_PRESETS = [(7, 62_500, 5), (8, 62_500, 8), (9, 125_000, 5), (11, 250_000, 5), (12, 125_000, 8)]


def airtime_generate() -> dict:
    """docs/vectors/lora-airtime-v1.json: LoRa time-on-air and MeshCore channel packet size, shared by
    bridge.py (lora_airtime_s, grp_data_len) and the Android bridge (Airtime.ms, channelPacketBytes)."""
    from libretag.meshcore import lora_airtime_s

    from .bridge import grp_data_len

    airtime = [{"bytes": n, "sf": sf, "bw_hz": bw, "cr": cr, "preamble": 16,
                "ms": round(lora_airtime_s(n, sf, bw, cr, 16) * 1000, 3)}
               for sf, bw, cr in AIRTIME_PRESETS for n in (1, 10, 40, 100, 183, 255)]
    packets = [{"datagram": d, "path_bytes": pb, "region": rg,
                "bytes": 1 + (4 if rg else 0) + 1 + pb + grp_data_len(d)}
               for d in (0, 12, 13, 29, 45, 165) for pb in (0, 3) for rg in (False, True)]
    return {
        "description": "LoRa time-on-air (Semtech formula, explicit header, CRC on, cr = 5..8 for 4/5..4/8) "
                       "and MeshCore channel packet bytes (header 1 + region 4 + path_len 1 + path + GRP_DATA). "
                       "Generated by `nostrmesh vectors --airtime`; do not edit by hand.",
        "version": 1,
        "airtime": airtime,
        "channel_packet": packets,
    }

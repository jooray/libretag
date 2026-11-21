import asyncio
import hashlib
import json
import struct
from pathlib import Path

import pytest

from nostrmesh import frag, frames, grp, road, secp
from nostrmesh.bridge import Bridge, geohash_cover
from nostrmesh.config import Config, Geofence
from nostrmesh.errors import FrameError
from nostrmesh.ledger import Ledger
from nostrmesh.nostr import Event, nip04_decrypt, nip04_dm, nip04_key, sign_event
from nostrmesh.radio import (CompanionDecoder, CompanionSerial, Datagram, Ether, KissRadio, MemoryRadio,
                             companion_frame)
from nostrmesh.registry import Device, Registry

VEC = json.loads((Path(__file__).parent / "vectors" / "road-events-v1.json").read_text())
T0 = 1_763_020_800


def key(n):
    return hashlib.sha256(f"nostrmesh test key {n}".encode()).digest()


# ---------------------------------------------------------------- crypto


def test_bip340_reproduces_roadbeacon_vectors():
    v = VEC["valid"][0]
    sk = hashlib.sha256(b"road-events-v1 test key 1").digest()
    assert secp.pubkey(sk).hex() == v["event"]["pubkey"]
    assert secp.sign(bytes.fromhex(v["event"]["id"]), sk).hex() == v["event"]["sig"]
    for v in VEC["valid"]:
        assert Event.from_dict(v["event"]).verify()


def test_ecdh_matches_cryptography_library():
    from cryptography.hazmat.primitives.asymmetric import ec

    a, b = key(1), key(2)
    pb = secp.pubkey(b)
    # lift_x picks even y, which is what NIP-04 does with x-only keys
    pt = secp.lift_x(int.from_bytes(pb, "big"))
    pub = ec.EllipticCurvePublicNumbers(pt[0], pt[1], ec.SECP256K1()).public_key()
    priv = ec.derive_private_key(int.from_bytes(a, "big"), ec.SECP256K1())
    assert priv.exchange(ec.ECDH(), pub) == secp.ecdh_x(a, pb)
    assert nip04_key(a, pb) == nip04_key(b, secp.pubkey(a))


def test_nip04_roundtrip():
    ev = nip04_dm(key(1), secp.pubkey(key(2)), "ahoj 🚗", T0)
    assert ev.verify()
    assert nip04_decrypt(nip04_key(key(2), secp.pubkey(key(1))), ev.content) == "ahoj 🚗"


# ---------------------------------------------------------------- road (Roadbeacon conformance vectors)


@pytest.mark.parametrize("v", VEC["valid"], ids=lambda v: v["name"])
def test_road_valid(v):
    ev = Event.from_dict(v["event"])
    assert road.encode(ev).hex() == v["frame"]
    back = frames.decode(bytes.fromhex(v["frame"]), frames.DATA_TYPE_ROAD).event
    assert back.to_dict() == v["event"]


@pytest.mark.parametrize("v", VEC["decode_only"], ids=lambda v: v["name"])
def test_road_decode_only(v):
    assert frames.decode(bytes.fromhex(v["frame"]), frames.DATA_TYPE_ROAD).event.to_dict() == v["event"]


@pytest.mark.parametrize("v", VEC["decode_invalid"], ids=lambda v: v["name"])
def test_road_decode_invalid(v):
    with pytest.raises(FrameError) as e:
        frames.decode(bytes.fromhex(v["frame"]), frames.DATA_TYPE_ROAD)
    assert e.value.code == v["error"]


@pytest.mark.parametrize("v", VEC["encode_invalid"], ids=lambda v: v["name"])
def test_road_encode_invalid(v):
    with pytest.raises(FrameError) as e:
        road.encode(Event.from_dict(v["event"]))
    assert e.value.code == v["error"]


def test_road_profile_inside_nostr_frame():
    v = VEC["valid"][1]
    fr = bytes.fromhex(v["frame"])
    d = frames.decode(bytes([frames.ROAD_REPORT]) + fr[1:])
    assert d.event.id == v["event"]["id"]


# ---------------------------------------------------------------- profiles


def test_note_sizes_and_roundtrip():
    ev = sign_event(key(1), T0, 1, [], "x" * 63)
    enc = frames.encode(ev)
    assert enc.profile == frames.NOTE and len(enc.frame) == 165
    assert frames.decode(enc.frame).event.to_dict() == ev.to_dict()
    with pytest.raises(FrameError):
        frames.encode(sign_event(key(1), T0, 1, [], "x" * 64))
    assert len(frames.encode(sign_event(key(1), T0, 1, [], "x" * 61), max_len=163).frame) == 163


def test_note_refuses_control_chars_allows_newline():
    frames.encode(sign_event(key(1), T0, 1, [], "a\nb\tc"))
    with pytest.raises(FrameError) as e:
        frames.encode(sign_event(key(1), T0, 1, [], "a\x07b"))
    assert e.value.code == "NON_CANONICAL"


def test_generic_roundtrip_and_sizes():
    tags = [["t", "ab"], ["p", key(3).hex()], ["d", "007"]]
    ev = sign_event(key(1), T0, 30078 & 0xFFFF, tags, "hi")
    enc = frames.encode(ev)
    assert enc.profile == frames.GENERIC
    assert frames.decode(enc.frame).event.to_dict() == ev.to_dict()
    # sizes from docs/nostr-mesh-bridge.md 1.4
    one = sign_event(key(1), T0, 7, [["t", "ab"]], "")
    assert len(frames.encode(one).frame) == 105 + 7
    upper = sign_event(key(1), T0, 7, [["e", key(3).hex()[:40].upper()]], "")
    assert frames.decode(frames.encode(upper).frame).event.id == upper.id


def test_generic_rejects_too_many_tags():
    ev = sign_event(key(1), T0, 7, [["t", str(i)] for i in range(16)], "")
    with pytest.raises(FrameError):
        frames.encode(ev)


def test_tampered_frame_fails_signature():
    enc = frames.encode(sign_event(key(1), T0, 1, [], "hello"))
    bad = bytearray(enc.frame)
    bad[40] ^= 1
    with pytest.raises(FrameError) as e:
        frames.decode(bytes(bad))
    assert e.value.code == "SIGNATURE_INVALID"


def test_dm4_full_and_ref_sizes():
    a, b = key(1), key(2)
    pb = secp.pubkey(b)
    ev15 = nip04_dm(a, pb, "x" * 15, T0)
    full = frames.encode(ev15, frames.DM4_FULL)
    assert len(full.frame) == 165
    assert frames.decode(full.frame).event.to_dict() == ev15.to_dict()
    with pytest.raises(FrameError):
        frames.encode(nip04_dm(a, pb, "x" * 16, T0), frames.DM4_FULL)

    reg = Registry([Device("dev", secp.pubkey(a), pb)])
    ev63 = nip04_dm(a, pb, "y" * 63, T0)
    ref = frames.encode(ev63, can_ref=reg.knows)
    assert ref.profile == frames.DM4_REF and len(ref.frame) == 157
    assert frames.decode(ref.frame, resolve=reg.resolve).event.to_dict() == ev63.to_dict()
    with pytest.raises(FrameError) as e:
        frames.decode(ref.frame, resolve=Registry().resolve)
    assert e.value.code == "UNKNOWN_REF"
    with pytest.raises(FrameError):
        frames.encode(nip04_dm(a, pb, "y" * 64, T0), frames.DM4_REF)


def test_dm4_ref_collision_resolved_by_signature():
    a, b = key(1), key(2)
    pa, pb = secp.pubkey(a), secp.pubkey(b)
    fake = pa[:4] + bytes(28)  # same ref, not on the curve as x maybe; signature decides anyway
    reg = Registry([Device("dev", pa, pb), Device("other", fake, pb)])
    ev = nip04_dm(a, pb, "hi", T0)
    fr = frames.encode(ev, frames.DM4_REF).frame
    assert frames.decode(fr, resolve=reg.resolve).event.pubkey == pa.hex()


def test_dm4_rejects_non_canonical_content():
    ev = nip04_dm(key(1), secp.pubkey(key(2)), "hi", T0)
    ct, iv = ev.content.split("?iv=")
    bad = sign_event(key(1), T0, 4, ev.tags, ct.rstrip("=") + "?iv=" + iv)
    with pytest.raises(FrameError) as e:
        frames.encode(bad, frames.DM4_FULL)
    assert e.value.code == "NON_CANONICAL_BASE64"


# ---------------------------------------------------------------- fragments


def test_fragments_reassemble_any_order_and_with_parity():
    a, b = key(1), key(2)
    ev = nip04_dm(a, secp.pubkey(b), "z" * 150, T0)
    fr = frames.encode(ev, frames.DM4_FULL, max_len=None).frame
    parts = frag.split(fr, 165, parity=True)
    assert len(parts) == 3 and all(len(p) <= 165 for p in parts)
    r = frag.Reassembler()
    assert r.feed(parts[1], 0) is None
    assert r.feed(parts[0], 1) == fr
    for missing in (0, 1):
        r = frag.Reassembler()
        got = [r.feed(p, 0) for i, p in enumerate(parts) if i != missing]
        assert got[-1] == fr
    d = frames.decode(fr, max_len=frames.MAX_REASSEMBLED)
    assert d.event.id == ev.id


def test_forged_fragment_does_not_kill_message():
    fr = bytes(range(256)) + bytes(50)
    parts = frag.split(fr, 165)
    forged = parts[0][:4] + b"\xff" * (len(parts[0]) - 4)
    r = frag.Reassembler()
    assert r.feed(forged, 0) is None
    assert r.feed(parts[1], 0) is None
    assert r.feed(parts[0], 0) == fr


def test_fragment_timeout():
    parts = frag.split(bytes(300), 165)
    r = frag.Reassembler(timeout_s=60)
    r.feed(parts[0], 0)
    assert r.feed(parts[1], 61) is None


# ---------------------------------------------------------------- radio adapters


class FakeSerial:
    """A companion node: answers the commands the adapter sends."""

    def __init__(self, channels):
        self.slots = {i: (n, grp.channel_key(n) if n else bytes(16)) for i, n in enumerate(channels)}
        self.out = bytearray()
        self.queue = []
        self.sent = []
        self.dec = CompanionDecoder()

    def _reply(self, payload):
        self.out += b">" + struct.pack("<H", len(payload)) + payload

    def write(self, data):
        buf = bytearray(data)
        while buf:
            ln = struct.unpack_from("<H", buf, 1)[0]
            cmd = bytes(buf[3:3 + ln])
            del buf[:3 + ln]
            op = cmd[0]
            if op == 0x01:
                self._reply(bytes([0x05]) + bytes(57) + b"node")
            elif op == 0x06:
                self._reply(b"\x00")
            elif op == 0x1F:
                i = cmd[1]
                if i in self.slots:
                    n, s = self.slots[i]
                    self._reply(bytes([0x12, i]) + n.encode().ljust(32, b"\x00") + s)
                else:
                    self._reply(b"\x01\x02")
            elif op == 0x20:
                self.slots[cmd[1]] = (cmd[2:34].rstrip(b"\x00").decode(), cmd[34:50])
                self._reply(b"\x00")
            elif op == 0x0A:
                self._reply(self.queue.pop(0) if self.queue else b"\x0a")
            elif op == 0x3E:
                self.sent.append(cmd)
                self._reply(b"\x00")

    def read(self, n):
        import time

        if not self.out:
            time.sleep(0.005)
            return b""
        chunk, self.out[:] = bytes(self.out[:n]), self.out[n:]
        return chunk

    def close(self):
        pass


def test_companion_adapter_handshake_receive_send():
    fake = FakeSerial(["Public", "#roadstr", ""])
    got = []
    cs = CompanionSerial("x", ["#nostr", "#roadstr"], add_channels=True, ser=fake, poll_s=0.05)
    payload = b"\x01" + bytes(101)
    fake.queue.append(bytes([0x1B, 8, 0, 0, 1, 2, 0x50, 0x01, 3]) + b"abc")      # #roadstr, 2 hops
    fake.queue.append(bytes([0x11, 0, 0]) + b"chat message, ignored")
    cs.start(got.append)
    import time

    for _ in range(100):
        if got:
            break
        time.sleep(0.01)
    assert cs.idx_by_name == {"#roadstr": 1, "#nostr": 2}
    # queued before the bridge connected: heard at an unknown time (stale), never a trigger
    assert got == [Datagram("#roadstr", 0x0150, b"abc", 2, 2.0, stale=True)]
    cs.send("#nostr", frames.DATA_TYPE_NOSTR, payload)
    assert fake.sent[-1] == bytes([0x3E, 2, 0xFF]) + struct.pack("<H", 0xFF4E) + payload
    cs.close()


def test_companion_refuses_without_channel():
    from nostrmesh.radio import RadioError

    cs = CompanionSerial("x", ["#nostr"], ser=FakeSerial(["Public"]))
    with pytest.raises(RadioError):
        cs.start(lambda dg: None)
    cs.close()


def test_kiss_adapter_wraps_and_dedupes():
    class Link:
        def __init__(self):
            self.out = []

        def send(self, raw):
            self.out.append(raw)

    a, b = KissRadio("x", ["#nostr"], link=Link()), KissRadio("y", ["#nostr"], link=Link())
    a.send("#nostr", frames.DATA_TYPE_NOSTR, b"hello")
    raw = a._link.out[0]
    got = b.handle_raw(raw)
    assert got.rx_time and got.route_type == "flood"
    got.rx_time = None
    assert got == Datagram("#nostr", frames.DATA_TYPE_NOSTR, b"hello", 0, path=[], route_type="flood")
    assert b.handle_raw(raw) is None  # flood copy
    assert a.handle_raw(raw) is None  # own echo


def test_companion_decoder_resyncs():
    d = CompanionDecoder()
    assert d.feed(b"junk>" + struct.pack("<H", 2)) == []
    assert d.feed(b"ok>\x01\x00z") == [b"ok", b"z"]
    assert companion_frame(b"ab") == b"<\x02\x00ab"


# ---------------------------------------------------------------- bridge


class FakePool:
    subscribe_urls = ["wss://r"]

    def __init__(self):
        self.published = []
        self.subs = {}

    async def publish(self, ev, retry=True):
        self.published.append(ev)
        return {"wss://r": (True, "")}

    async def subscribe(self, sid, filters):
        self.subs[sid] = filters


def make_bridge(now=T0 + 10, downlink=False, registry=None, radio=None, rng_seed=1):
    import random

    cfg = Config()
    cfg.downlink.enabled = downlink
    cfg.downlink.hold_min_s = cfg.downlink.hold_max_s = 0.01
    cfg.downlink.dm_jitter_s = 0
    cfg.downlink.min_gap_s = 0
    cfg.downlink.fragment_gap_s = 0
    cfg.downlink.geofence = Geofence(48.70, 21.25, 1)  # report_minimal inside, report_maxspeed_comment 2 km away
    for p in cfg.downlink.profiles:
        cfg.downlink.profiles[p] = True
    cfg.airtime.budget_pct = 100  # the budget has its own tests
    cfg.libretag.publish_delay_max_s = 0.01
    cfg.libretag.claim_wait_s = 0.02
    clock = {"t": now}
    br = Bridge(cfg, radio or MemoryRadio(), FakePool(), Ledger(), registry or Registry(),
                clock=lambda: clock["t"], rng=random.Random(rng_seed))
    br.clock_ref = clock
    return br


def run(coro):
    return asyncio.run(coro)


def road_vec(name):
    return next(v for v in VEC["valid"] if v["name"] == name)


def test_uplink_publishes_verified_road_report_once():
    v = road_vec("report_minimal")
    ev = v["event"]

    async def go():
        br = make_bridge(now=ev["created_at"] + 60)
        await br.start()
        dg = Datagram("#roadstr", 0x0150, bytes.fromhex(v["frame"]), 3)
        await br.on_datagram(dg)
        await br.on_datagram(dg)  # second gateway copy / flood copy
        await br.drain()
        return br

    br = run(go())
    assert br.pool.published == [ev]
    assert br.stats.c["rx_duplicate"] == 1
    assert br.ledger.get(ev["id"])["published_at"]


def test_uplink_drops_bad_signature_stale_and_disabled_profiles():
    v = road_vec("report_minimal")
    fr = bytearray.fromhex(v["frame"])
    fr[-1] ^= 1

    async def go():
        br = make_bridge(now=v["event"]["created_at"] + 60)
        await br.start()
        await br.on_datagram(Datagram("#roadstr", 0x0150, bytes(fr)))
        stale = make_bridge(now=v["event"]["created_at"] + 5 * 3600)  # police lives 4 h
        await stale.start()
        await stale.on_datagram(Datagram("#roadstr", 0x0150, bytes.fromhex(v["frame"])))
        note = frames.encode(sign_event(key(1), v["event"]["created_at"], 1, [], "hi")).frame
        await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, note))
        await br.drain()
        return br, stale

    br, stale = run(go())
    assert br.pool.published == [] and stale.pool.published == []
    assert br.stats.c["rx_bad_SIGNATURE_INVALID"] == 1
    assert stale.stats.c["rx_stale"] == 1
    assert br.stats.c["up_disabled_note"] == 1


def test_uplink_rate_limit_per_pubkey():
    async def go():
        br = make_bridge()
        await br.start()
        br.cfg.uplink.profiles["note"] = True
        for i in range(8):
            fr = frames.encode(sign_event(key(1), T0 + i, 1, [], f"n{i}")).frame
            await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, fr))
        await br.drain()
        return br

    br = run(go())
    assert len(br.pool.published) == 6
    assert br.stats.c["up_rate_limited_pubkey"] == 2


def test_uplink_dm_ref_fragmented_from_registered_device():
    dev_sk, owner_sk = key(10), key(11)
    reg = Registry([Device("sensor1", secp.pubkey(dev_sk), secp.pubkey(owner_sk))])
    ev = nip04_dm(dev_sk, secp.pubkey(owner_sk), "teplota 61 C, " + "x" * 120, T0)
    fr = frames.encode(ev, frames.DM4_REF, max_len=None).frame
    parts = frag.split(fr, 165)

    async def go():
        br = make_bridge(registry=reg)
        await br.start()
        for p in reversed(parts):
            await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, p))
        await br.drain()
        return br

    br = run(go())
    assert [e["id"] for e in br.pool.published] == [ev.id]


def test_uplink_dm_from_unknown_key_not_published():
    ev = nip04_dm(key(20), secp.pubkey(key(21)), "spam", T0)

    async def go():
        br = make_bridge()
        await br.start()
        await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, frames.encode(ev, frames.DM4_FULL).frame))
        await br.drain()
        return br

    br = run(go())
    assert br.pool.published == [] and br.stats.c["up_dm_unregistered"] == 1


def test_downlink_off_by_default():
    v = road_vec("report_minimal")

    async def go():
        br = make_bridge(now=v["event"]["created_at"] + 60)
        await br.start()
        return br, await br.on_relay_event(v["event"])

    br, out = run(go())
    assert out is None and br.radio.sent == []
    assert set(br.pool.subs) == {"nm-lt-report", "nm-lt-cmd", "nm-lt-del", "nm-lt-claim", "nm-lt-ann"}  # LT2 only, no road/DM


def test_downlink_road_in_fence_with_hold_and_loop_prevention():
    v = road_vec("report_minimal")  # inside the 1 km fence
    far = road_vec("report_maxspeed_comment")  # 2 km away, outside the fence

    async def go():
        br = make_bridge(now=v["event"]["created_at"] + 60, downlink=True)
        await br.start()
        assert "nm-road" in br.pool.subs and "nm-dm" not in br.pool.subs
        await br.on_relay_event(v["event"])
        await br.on_relay_event(v["event"])  # same event from a second relay
        await br.on_relay_event(far["event"])
        await br.drain()
        # heard back on the radio: must not be re-sent or re-published
        await br.on_datagram(Datagram("#roadstr", 0x0150, bytes.fromhex(v["frame"])))
        await br.drain()
        return br

    br = run(go())
    assert [d.payload.hex() for d in br.radio.sent] == [v["frame"]]
    assert br.stats.c["dn_outside_fence"] == 1
    assert br.pool.published == []


def test_downlink_skips_when_heard_during_hold():
    v = road_vec("report_minimal")

    async def go():
        br = make_bridge(now=v["event"]["created_at"] + 60, downlink=True)
        br.cfg.downlink.hold_min_s = br.cfg.downlink.hold_max_s = 0.2
        await br.start()
        await br.on_relay_event(v["event"])
        await asyncio.sleep(0.05)
        await br.on_datagram(Datagram("#roadstr", 0x0150, bytes.fromhex(v["frame"])))  # another bridge sent it
        await br.drain()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["dn_heard_meanwhile"] == 1


def test_two_bridges_one_transmits():
    v = road_vec("report_minimal")

    async def go():
        ether = Ether()
        a = make_bridge(now=v["event"]["created_at"] + 60, downlink=True, radio=MemoryRadio(ether), rng_seed=1)
        b = make_bridge(now=v["event"]["created_at"] + 60, downlink=True, radio=MemoryRadio(ether), rng_seed=2)
        a.cfg.downlink.hold_min_s, a.cfg.downlink.hold_max_s = 0.01, 0.02
        b.cfg.downlink.hold_min_s, b.cfg.downlink.hold_max_s = 0.2, 0.3
        for br in (a, b):
            await br.start()
            await br.on_relay_event(v["event"])
        await a.drain()
        await b.drain()
        return ether

    ether = run(go())
    assert len(ether.log) == 1


def test_downlink_dm_waits_for_device_rendezvous_and_fragments():
    dev_sk, owner_sk, stranger = key(30), key(31), key(32)
    reg = Registry([Device("sensor1", secp.pubkey(dev_sk), secp.pubkey(owner_sk), [secp.pubkey(owner_sk)])])
    dm = nip04_dm(owner_sk, secp.pubkey(dev_sk), "zapni kurenie na 21 stupnov " + "." * 100, T0)
    spam = nip04_dm(stranger, secp.pubkey(dev_sk), "hi", T0)
    uplink = frames.encode(nip04_dm(dev_sk, secp.pubkey(owner_sk), "online", T0 + 5), frames.DM4_REF).frame

    async def go():
        br = make_bridge(downlink=True, registry=reg)
        await br.start()
        assert br.pool.subs["nm-dm"][0]["#p"] == [secp.pubkey(dev_sk).hex()]
        await br.on_relay_event(dm.to_dict())
        await br.on_relay_event(spam.to_dict())
        await br.drain()
        assert br.radio.sent == []  # device asleep
        await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, uplink))
        await br.drain()
        return br

    br = run(go())
    assert br.stats.c["dn_dm_not_allowed"] == 1
    assert len(br.radio.sent) == 2  # 2 fragments
    r = frag.Reassembler()
    whole = [r.feed(d.payload, 0) for d in br.radio.sent][-1]
    got = frames.decode(whole, resolve=reg.resolve, max_len=frames.MAX_REASSEMBLED).event
    assert got.id == dm.id
    assert nip04_decrypt(nip04_key(dev_sk, secp.pubkey(owner_sk)), got.content).startswith("zapni")


def test_downlink_rate_limit():
    async def go():
        br = make_bridge(downlink=True)
        br.cfg.downlink.rate_global_per_hour = 2
        br.tx_global.count = 2
        await br.start()
        for i in range(3):
            ev, fr = _road_event(key(40 + i), br.clock_ref["t"] - 10)
            await br.on_relay_event(ev.to_dict())
        await br.drain()
        return br

    br = run(go())
    assert len(br.radio.sent) == 2 and br.stats.c["dn_rate_limited"] == 1


def _road_event(sk, created_at):
    exp = created_at + 1_209_600
    tags = road.report_tags(48_700_000, 21_250_000, "police", exp)
    ev = sign_event(sk, created_at, 1315, tags, "")
    return ev, road.encode(ev)


def test_geohash_cover_includes_center_cell():
    cells = geohash_cover(48.70, 21.25, 40)
    assert road.geohash(48.70, 21.25, 4) in cells and 2 <= len(cells) <= 20


def test_config_example_loads():
    cfg = Config.load(Path(__file__).parents[1] / "bridge.example.toml")
    assert cfg.downlink.enabled is False and cfg.uplink.profiles["note"] is False


# ---------------------------------------------------------------- frame vectors (docs/vectors/nostr-frames-v1.json)

FV = json.loads((Path(__file__).parents[2] / "docs" / "vectors" / "nostr-frames-v1.json").read_text())


def _reg(v):
    return Registry([Device(f"k{i}", bytes.fromhex(k)) for i, k in enumerate(v.get("registry", []))])


def test_frame_vectors_are_reproducible():
    from nostrmesh.vectors import generate

    assert json.loads(json.dumps(generate(), ensure_ascii=False)) == FV


@pytest.mark.parametrize("v", FV["valid"], ids=lambda v: v["name"])
def test_frame_vectors_valid(v):
    ev = Event.from_dict(v["event"])
    assert frames.encode(ev, frames.PROFILE_IDS[v["profile"]]).frame.hex() == v["frame"]
    assert frames.decode(bytes.fromhex(v["frame"]), resolve=_reg(v).resolve).event.to_dict() == v["event"]


@pytest.mark.parametrize("v", FV["decode_invalid"], ids=lambda v: v["name"])
def test_frame_vectors_decode_invalid(v):
    with pytest.raises(FrameError) as e:
        frames.decode(bytes.fromhex(v["frame"]), resolve=_reg(v).resolve)
    assert e.value.code == v["error"]


@pytest.mark.parametrize("v", FV["encode_invalid"], ids=lambda v: v["name"])
def test_frame_vectors_encode_invalid(v):
    with pytest.raises(FrameError) as e:
        frames.encode(Event.from_dict(v["event"]), frames.PROFILE_IDS.get(v.get("profile")))
    assert e.value.code == v["error"]


def test_frame_vectors_fragments():
    v = FV["fragments"][0]
    parts = [bytes.fromhex(p) for p in v["fragments"]]
    assert frag.split(bytes.fromhex(v["frame"]), 165, parity=True) == parts
    r = frag.Reassembler()
    assert [r.feed(p, 0) for p in parts[1:]][-1].hex() == v["frame"]  # first lost, parity recovers it
    assert frames.decode(bytes.fromhex(v["frame"]), max_len=frames.MAX_REASSEMBLED).event.to_dict() == v["event"]


# ---------------------------------------------------------------- real websocket relay (localhost)


def test_end_to_end_with_local_relay():
    """radio frame -> bridge -> relay; relay DM -> bridge -> radio, over real websockets."""
    from websockets.asyncio.server import serve

    from nostrmesh.relays import RelayPool

    dev_sk, owner_sk = key(50), key(51)
    reg = Registry([Device("dev", secp.pubkey(dev_sk), secp.pubkey(owner_sk), [secp.pubkey(owner_sk)],
                           rendezvous=False)])
    stored, subs = [], {}

    async def relay(ws):
        async for raw in ws:
            msg = json.loads(raw)
            if msg[0] == "EVENT":
                dup = any(e["id"] == msg[1]["id"] for e in stored)
                stored.append(msg[1])
                await ws.send(json.dumps(["OK", msg[1]["id"], True, "duplicate: have it" if dup else ""]))
                for sid, (sock, filters) in subs.items():
                    if any(msg[1]["kind"] in f.get("kinds", []) for f in filters):
                        await sock.send(json.dumps(["EVENT", sid, msg[1]]))
            elif msg[0] == "REQ":
                subs[msg[1]] = (ws, msg[2:])
                await ws.send(json.dumps(["EOSE", msg[1]]))

    async def go():
        async with serve(relay, "127.0.0.1", 0) as server:
            url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}"
            br = make_bridge(downlink=True, registry=reg)
            br.cfg.downlink.profiles = {"dm4.ref": True}
            pool = RelayPool([url], [url], on_event=lambda ev, u: br.on_relay_event(ev, u))
            br.pool = pool
            pool.start()
            await br.start()
            uplink = nip04_dm(dev_sk, secp.pubkey(owner_sk), "alarm: voda v pivnici", T0)
            await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, frames.encode(uplink, frames.DM4_REF).frame))
            await br.drain()
            for _ in range(100):  # wait for the REQ to land
                if "nm-dm" in subs:
                    break
                await asyncio.sleep(0.01)
            # the owner answers from a normal Nostr client
            reply = nip04_dm(owner_sk, secp.pubkey(dev_sk), "ok, idem tam", T0 + 30)
            other = RelayPool([url])
            other.start()
            await other.publish(reply.to_dict())
            for _ in range(200):
                await asyncio.sleep(0.01)
                await br.drain()
                if br.radio.sent:
                    break
            await other.stop()
            await br.stop()
            await pool.stop()
            return br, uplink, reply

    br, uplink, reply = run(go())
    assert stored[0]["id"] == uplink.id
    assert br.ledger.get(uplink.id)["published_at"]
    assert len(br.radio.sent) == 1
    got = frames.decode(br.radio.sent[0].payload, resolve=reg.resolve).event
    assert got.id == reply.id



# ---------------------------------------------------------------- direct replies, inbox poll, local API


def test_kiss_direct_packet_reverses_route():
    from libretag.meshcore import ROUTE_DIRECT, Packet

    class Link:
        out = []

        def send(self, raw):
            self.out.append(raw)

    k = KissRadio("x", ["#nostr"], link=Link())
    k.send_direct("#nostr", frames.DATA_TYPE_NOSTR, b"dm", [b"\x33", b"\x22"])
    pkt = Packet.from_bytes(Link.out[-1])
    assert pkt.route_type == ROUTE_DIRECT and pkt.path == [b"\x33", b"\x22"]
    assert grp.unwrap(grp.channel_key("#nostr"), pkt.payload) == (frames.DATA_TYPE_NOSTR, b"dm")


def _poll(sk, ca):
    return frames.encode(sign_event(sk, ca, frames.INBOX_POLL_KIND, [], "")).frame


def test_inbox_poll_flushes_queue_direct_and_is_not_published():
    from nostrmesh.radio import MemoryDirectRadio

    dev_sk, owner_sk = key(60), key(61)
    reg = Registry([Device("tag", secp.pubkey(dev_sk), secp.pubkey(owner_sk), [secp.pubkey(owner_sk)])])
    dm = nip04_dm(owner_sk, secp.pubkey(dev_sk), "status", T0)

    async def go():
        br = make_bridge(downlink=True, registry=reg, radio=MemoryDirectRadio())
        await br.start()
        await br.on_relay_event(dm.to_dict())
        await br.drain()
        assert br.radio.sent == []
        # stale poll (replayed) does not open the queue
        await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, _poll(dev_sk, T0 - 3600), 2,
                                      path=[b"\x11", b"\x22"]))
        await br.drain()
        assert br.radio.sent == []
        await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, _poll(dev_sk, T0 + 5), 2,
                                      path=[b"\x11", b"\x22"]))
        await br.drain()
        return br

    br = run(go())
    assert br.pool.published == []
    assert br.stats.c["rx_inbox_poll"] == 2
    assert len(br.radio.sent) == 1 and br.radio.sent[0].path == [b"\x22", b"\x11"]
    assert br.stats.c["dn_sent_direct"] == 1


CAPS = ["contacts", "advert_send", "regions", "channels_join", "replay", "radio_stats", "tx_path_data",
        "tx_path_text", "rx_path", "rx_meta", "raw_rx", "raw_tx", "tx_result", "airtime", "duty_cap", "noise_floor"]


def test_local_api_adapter_pairs_joins_receives_sends(tmp_path):
    import base64 as b64
    import threading
    import time

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from websockets.sync.server import serve

    from nostrmesh.localapi import Identity, LocalApiRadio

    log = {"sent": [], "joined": [], "verified": False}
    channels = {"#roadstr"}  # #nostr missing until joined

    def handler(ws):
        hello = json.loads(ws.recv())
        pub = b64.b64decode(hello["client"]["publicKey"])
        ws.send(json.dumps({"type": "pairing.challenge", "nonce": "abcdefghijklmnop1234",
                            "message": "meshcore-local-api-pair-v1:abcdefghijklmnop1234"}))
        pair = json.loads(ws.recv())
        key_ = ec.EllipticCurvePublicNumbers(int.from_bytes(pub[:32], "big"), int.from_bytes(pub[32:], "big"),
                                             ec.SECP256R1()).public_key()
        key_.verify(b64.b64decode(pair["signature"]), b"meshcore-local-api-pair-v1:abcdefghijklmnop1234",
                    ec.ECDSA(hashes.SHA256()))
        log["verified"] = True
        # as meshcored: no radio in hello.device, the frequency only from device.info
        ws.send(json.dumps({"type": "hello", "protocol": 1, "capabilities": CAPS, "device": {
            "connected": True, "name": "node"}}))
        for raw in ws:
            m = json.loads(raw)
            meth, p, rid = m.get("method"), m.get("params", {}), m.get("id")
            if meth == "events.subscribe":
                if p["channel"] not in channels:
                    ws.send(json.dumps({"id": rid, "ok": False, "error": {"code": "unknown_channel"}}))
                else:
                    ws.send(json.dumps({"id": rid, "ok": True, "result": {"subscriptionId": "s" + p["channel"]}}))
                    if p["channel"] == "#nostr":
                        ws.send(json.dumps({"type": "event", "subscriptionId": "s#nostr", "event": {
                            "type": "channel.data", "channel": "#nostr", "dataType": 0xFF4E,
                            "payload": b64.b64encode(b"frame").decode(), "hops": 1,
                            "receivedAt": "2026-11-13T10:00:00.000Z"}}))
            elif meth == "channels.join":
                channels.add(p["name"])
                log["joined"].append(p["name"])
                ws.send(json.dumps({"id": rid, "ok": True, "result": {}}))
            elif meth == "channel.sendData":
                log["sent"].append(p)
                ws.send(json.dumps({"id": rid, "ok": True, "result": {}}))
            elif meth == "device.info":
                ws.send(json.dumps({"id": rid, "ok": True, "result": {
                    "connected": True, "name": "node", "radio": {"freqHz": 869618000, "sf": 7, "bwHz": 62500, "cr": 5}}}))
            elif meth == "radio.stats":
                ws.send(json.dumps({"id": rid, "ok": True, "result": {"preset": {"sf": 7, "bwHz": 62500, "cr": 5}}}))
            else:
                ws.send(json.dumps({"id": rid, "ok": False, "error": {"code": "unknown_method"}}))

    with serve(handler, "127.0.0.1", 0) as server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = f"ws://127.0.0.1:{server.socket.getsockname()[1]}/api/v1"
        ident = Identity(tmp_path / "id.json")
        radio = LocalApiRadio(url, {"#nostr": [0xFF4E], "#roadstr": [0x0150]}, ident)
        got = []
        radio.start(got.append)
        for _ in range(300):
            if got and radio.subscribed == {"#nostr", "#roadstr"} and radio.preset:
                break
            time.sleep(0.01)
        radio.send("#nostr", 0xFF4E, b"out")
        radio.close()
        server.shutdown()

    assert log["verified"] and log["joined"] == ["#nostr"]
    assert radio.preset == (7, 62500.0, 5) and radio.freq_hz == 869618000 and radio.supports_direct
    assert got == [Datagram("#nostr", 0xFF4E, b"frame", 1, None, rx_time=1794564000.0)]  # 2026-11-13T10:00Z
    assert log["sent"] == [{"channel": "#nostr", "dataType": 0xFF4E, "payload": b64.b64encode(b"out").decode()}]
    assert Identity(tmp_path / "id.json").cursor("#nostr") == "2026-11-13T10:00:00.000Z"
    assert len(ident.fingerprint) == 19


# ---------------------------------------------------------------- expiring DMs (dm4x) and inbox relays


def test_dm4x_roundtrip_and_auto_profile():
    a, b = key(30), key(31)
    ev = nip04_dm(a, secp.pubkey(b), "battery 9 %", T0, expiration=T0 + frames.DM4X_TTL_S)
    full = frames.encode(ev)
    assert full.profile == frames.DM4X_FULL
    ref = frames.encode(ev, can_ref=lambda pk: True)
    assert ref.profile == frames.DM4X_REF and ref.frame[0] == 0x13
    plain = frames.encode(nip04_dm(a, secp.pubkey(b), "battery 9 %", T0), can_ref=lambda pk: True)
    assert len(plain.frame) == len(ref.frame)  # the expiration costs nothing on air
    back = frames.decode(ref.frame, resolve=lambda r: [secp.pubkey(a), secp.pubkey(b)])
    assert back.event.id == ev.id and back.policy_name == "dm4.ref"
    assert back.event.tags[1] == ["expiration", str(T0 + 14 * 86400)]
    other = nip04_dm(a, secp.pubkey(b), "x", T0, expiration=T0 + 3600)
    with pytest.raises(FrameError):
        frames.encode(other, frames.DM4X_FULL)


def test_inbox_relays_parse_and_filter():
    from nostrmesh import inbox

    sk, other = key(60), key(61)
    pk = secp.pubkey(sk).hex()

    def lst(kind, created_at, tags, k=sk):
        return sign_event(k, created_at, kind, tags, "").to_dict()

    forged = lst(10050, 50, [["relay", "wss://forged.example"]])
    forged["tags"] = [["relay", "wss://evil.example"]]  # a relay rewrote the list: signature no longer valid
    evs = [
        lst(10002, 5, [["r", "wss://old.example"]]),
        lst(10002, 9, [
            ["r", "wss://read.example/", "read"], ["r", "wss://write.example", "write"], ["r", "wss://both.example"],
            ["r", "ws://plain.example"], ["r", "wss://localhost"], ["r", "wss://192.168.1.5"], ["r", "wss://nas.local"],
            ["r", "wss://[fd00::1]"], ["r", "wss://8.8.8.8:7777"]]),
        lst(10050, 1, [["relay", "wss://auth.nostr1.com"], ["r", "wss://x.io"]]),
        lst(10050, 99, [["relay", "wss://evil.example"]], k=other),
        forged,
    ]
    got = inbox.parse(evs, pk)
    assert got == ["wss://auth.nostr1.com", "wss://read.example", "wss://both.example", "wss://8.8.8.8:7777"]
    assert inbox.extra(got, ["wss://both.example/"], limit=2) == ["wss://auth.nostr1.com", "wss://read.example"]
    c = inbox.InboxCache()
    c.put(pk, got, 0)
    assert c.get(pk, 3600) == got and c.get(pk, inbox.TTL_S + 1) is None
    c.put(pk, [], 0)
    assert c.get(pk, 60) == [] and c.get(pk, inbox.RETRY_S + 1) is None


def test_inbox_relays_dns_must_resolve_to_public_addresses():
    from nostrmesh import inbox

    table = {"pub.example": ["93.184.216.34"], "lan.example": ["192.168.1.7"], "mixed.example": ["1.1.1.1", "10.0.0.1"],
             "v6.example": ["2606:4700::1"], "v6lan.example": ["fd00::5"]}

    async def gai(host, port):
        if host not in table:
            raise OSError("NXDOMAIN")
        return [(2, 1, 6, "", (a, port)) for a in table[host]]

    urls = ["wss://pub.example", "wss://lan.example", "wss://mixed.example", "wss://v6.example",
            "wss://v6lan.example", "wss://gone.example", "wss://8.8.8.8:7777"]
    assert run(inbox.public_only(urls, gai)) == ["wss://pub.example", "wss://v6.example", "wss://8.8.8.8:7777"]


class InboxPool(FakePool):
    publish_urls = ["wss://r"]

    def __init__(self, lists):
        super().__init__()
        self.lists, self.queries, self.once = lists, [], []

    async def query(self, filters, timeout=6.0):
        self.queries.append(filters)
        return self.lists

    async def publish_once(self, ev, urls, timeout=15.0):
        self.once.append((ev["id"], list(urls)))
        return {u: (True, "") for u in urls}


def test_uplink_low_battery_dm_goes_to_owner_inbox_relays_too():
    dev_sk, owner_sk = key(40), key(41)
    owner = secp.pubkey(owner_sk).hex()
    reg = Registry([Device("tag", secp.pubkey(dev_sk), secp.pubkey(owner_sk))])
    lists = [sign_event(owner_sk, 1, 10050, [["relay", "wss://inbox.example"], ["relay", "wss://r"]], "").to_dict()]
    ev1 = nip04_dm(dev_sk, secp.pubkey(owner_sk), "battery 9 %", T0, expiration=T0 + frames.DM4X_TTL_S)
    ev2 = nip04_dm(dev_sk, secp.pubkey(owner_sk), "battery 4 %", T0 + 1, expiration=T0 + 1 + frames.DM4X_TTL_S)

    async def go():
        br = make_bridge(registry=reg)
        br.pool = InboxPool(lists)

        async def gai(host, port):
            return [(2, 1, 6, "", ("93.184.216.34", port))]

        br.resolver = gai
        await br.start()
        for ev in (ev1, ev2):
            fr = frames.encode(ev, frames.DM4X_REF, max_len=None).frame
            for p in frag.split(fr, 165) if len(fr) > 165 else [fr]:
                await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, p))
        await br.drain()
        return br

    br = run(go())
    assert [e["id"] for e in br.pool.published] == [ev1.id, ev2.id]
    assert br.pool.published[0]["tags"][1][0] == "expiration"
    assert br.pool.once == [(ev1.id, ["wss://inbox.example"]), (ev2.id, ["wss://inbox.example"])]
    assert len(br.pool.queries) == 1  # cached


def test_relay_query_until_eose_and_publish_once():
    from websockets.asyncio.server import serve

    from nostrmesh.relays import RelayPool

    stored = [{"id": "1" * 64, "kind": 10050, "pubkey": "ab" * 32, "created_at": 1, "tags": []}]
    got_events = []

    async def relay(ws):
        async for raw in ws:
            msg = json.loads(raw)
            if msg[0] == "REQ":
                for e in stored:
                    await ws.send(json.dumps(["EVENT", msg[1], e]))
                await ws.send(json.dumps(["EOSE", msg[1]]))
            elif msg[0] == "EVENT":
                got_events.append(msg[1])
                await ws.send(json.dumps(["OK", msg[1]["id"], True, ""]))

    async def go():
        async with serve(relay, "127.0.0.1", 0) as server:
            url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}"
            pool = RelayPool([url], [url])
            pool.start()
            q = await pool.query([{"kinds": [10050]}], timeout=5)
            once = await pool.publish_once({"id": "2" * 64, "kind": 4}, [url], timeout=5)
            await pool.stop()
            return q, once

    q, once = run(go())
    assert q == stored
    assert once[next(iter(once))][0] is True and got_events[0]["id"] == "2" * 64


def test_expiring_dm_from_unknown_key_is_published_plain_dm_is_not():
    a, b = key(60), secp.pubkey(key(61))
    exp = nip04_dm(a, b, "batt 9%", T0, expiration=T0 + frames.DM4X_TTL_S)
    plain = nip04_dm(a, b, "batt 9%", T0 + 1)

    async def go(flag):
        br = make_bridge()
        br.cfg.uplink.unregistered_expiring_dm = flag
        await br.start()
        for ev in (exp, plain):
            fr = frames.encode(ev, frames.DM4X_FULL if len(ev.tags) == 2 else frames.DM4_FULL).frame
            assert len(fr) <= 165
            await br.on_datagram(Datagram("#nostr", frames.DATA_TYPE_NOSTR, fr))
        await br.drain()
        return br

    br = run(go(True))
    assert [e["id"] for e in br.pool.published] == [exp.id]
    assert br.stats.c["up_dm_unregistered"] == 1
    assert run(go(False)).pool.published == []


def test_road_vectors_regenerate_and_are_synthetic():
    from nostrmesh import road_vectors

    text = (Path(__file__).parent / "vectors" / "road-events-v1.json").read_text()
    assert road_vectors.dumps() == text
    for v in VEC["valid"] + VEC["decode_only"]:
        t = dict((x[0], x[1]) for x in v["event"]["tags"] if len(x) >= 2)
        if "lat" in t and abs(float(t["lat"])) < 80 and float(t["lat"]) > 0:
            assert abs(float(t["lat"]) - 48.70) < 0.1 and abs(float(t["lon"]) - 21.25) < 0.1, v["name"]


# ---------------------------------------------------------------- service templates (host/deploy)


def test_deploy_templates_restart_on_watchdog_exit():
    import configparser
    import plistlib
    import re

    from nostrmesh.cli import EXIT_WATCHDOG

    deploy = Path(__file__).parents[1] / "deploy"
    unit = configparser.ConfigParser(strict=False, interpolation=None)
    unit.optionxform = str
    unit.read(deploy / "nostrmesh.service")
    svc = unit["Service"]
    assert svc["Restart"] == "on-failure" and svc["RestartForceExitStatus"] == str(EXIT_WATCHDOG)
    assert "nostrmesh run -c" in svc["ExecStart"] and unit["Unit"]["StartLimitIntervalSec"] == "0"
    pl = plistlib.loads((deploy / "local.nostrmesh.plist").read_bytes())
    assert pl["KeepAlive"] == {"SuccessfulExit": False} and pl["ThrottleInterval"] >= 10
    assert pl["ProgramArguments"][-4:-1] == ["nostrmesh", "run", "-c"]
    for text in ((deploy / "nostrmesh.service").read_text(), (deploy / "local.nostrmesh.plist").read_text()):
        # templates only: no real user, home directory or host name
        assert "/Users/YOU" in text or "%h" in text
        assert not re.search(r"/Users/(?!YOU\b)|/home/[a-z]", text)


# ---------------------------------------------------------------- airtime preset (shared with Airtime.ms)


def test_airtime_vectors_match_lora_formula_and_packet_size():
    from libretag.meshcore import lora_airtime_s
    from nostrmesh.bridge import grp_data_len
    from nostrmesh.vectors import airtime_generate

    path = Path(__file__).parents[2] / "docs" / "vectors" / "lora-airtime-v1.json"
    vec = json.loads(path.read_text())
    assert vec == json.loads(json.dumps(airtime_generate()))
    for v in vec["airtime"]:
        ms = lora_airtime_s(v["bytes"], v["sf"], v["bw_hz"], v["cr"], v["preamble"]) * 1000
        assert abs(ms - v["ms"]) <= v["ms"] * 0.01
    for v in vec["channel_packet"]:
        assert 1 + (4 if v["region"] else 0) + 1 + v["path_bytes"] + grp_data_len(v["datagram"]) == v["bytes"]


def test_airtime_default_is_the_live_preset_and_old_config_loads(tmp_path):
    c = Config()
    assert (c.airtime.sf, c.airtime.bw_hz, c.airtime.cr) == (7, 62_500, 5)
    old = tmp_path / "old.toml"
    old.write_text('[airtime]\nbudget_pct = 0.1\nsf = 8\nbw_hz = 62500\ncr = 8\n')
    c = Config.load(old)
    assert (c.airtime.sf, c.airtime.cr) == (8, 8)
    ex = Config.load(Path(__file__).parents[1] / "bridge.example.toml")
    assert (ex.airtime.sf, ex.airtime.bw_hz, ex.airtime.cr) == (7, 62_500, 5)
    assert ex.libretag.rx_per_first_hop_per_min == 10


def test_node_preset_overrides_config_with_one_warning(caplog):
    br = make_bridge()
    br.cfg.airtime.sf, br.cfg.airtime.cr = 8, 8
    slow = br.airtime_s(40)
    br.radio.preset = (7, 62_500.0, 5)
    with caplog.at_level("WARNING", logger="nostrmesh.bridge"):
        fast = br.airtime_s(40)
        br.airtime_s(40)
    assert fast < slow
    assert sum("using the node's preset" in r.message for r in caplog.records) == 1
    br.cfg.airtime.sf, br.cfg.airtime.cr = 7, 5
    assert br.airtime_s(40) == fast


def test_local_api_preset_parsing():
    from nostrmesh.localapi import parse_preset

    assert parse_preset({"preset": {"sf": 7, "bwHz": 62500, "cr": 5}}) == (7, 62500.0, 5)
    assert parse_preset({"preset": {"sf": 7, "bwHz": 62500, "cr": 1}}) == (7, 62500.0, 5)
    for bad in (None, {}, {"preset": {"sf": 13, "bwHz": 1, "cr": 5}}, {"preset": {"sf": 7, "bwHz": "x", "cr": 5}}):
        assert parse_preset(bad) is None
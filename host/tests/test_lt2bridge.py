"""nostrmesh bridge and LibreTag LT2 (PROTOCOL.md 4, SPEC 6.2): uplink variant B, mailbox, claims."""
import asyncio
import base64
import hashlib
import json
import random

import pytest

from libretag import lt2
from libretag.lt2 import Command, Keys, Report
from nostrmesh import lt2svc, secp
from nostrmesh.bridge import Bridge
from nostrmesh.config import Config
from nostrmesh.ledger import Ledger
from nostrmesh.localapi import LocalApiRadio
from nostrmesh.nostr import Event, sign_event
from nostrmesh.radio import Datagram, Ether, MemoryDirectRadio, MemoryRadio, RadioError

T0 = 1_763_200_000
KEYS = Keys(bytes(range(32)))
LT = ("#libretag", 0xFF4C)


def report(n, j=0):
    return lt2.seal_report_keys(KEYS, n, j, Report(has_fix=True, fix_time=T0, lat=48.7, lon=21.25))


def command(n, seq=1):
    return lt2.seal_command(KEYS.cmd(lt2.epoch(n)), n, Command.buzz(seq, 3))


def k(i):
    return hashlib.sha256(f"lt2bridge test {i}".encode()).digest()


class Pool:
    subscribe_urls = ["wss://a", "wss://b"]

    def __init__(self):
        self.published, self.subs = [], {}

    async def publish(self, ev, retry=True):
        self.published.append(ev)
        return {"wss://a": (True, "")}

    async def subscribe(self, sid, filters):
        self.subs[sid] = filters


# ---------------------------------------------------------------- signed mailbox helpers (PROTOCOL.md 4.2)

_N_OF: dict[bytes, int] = {}


def n_of(tag: bytes) -> int | None:
    """Report counter of a test report tag x(n, j) (KEYS, n < 1200), or None."""
    if not _N_OF:
        for e in range(1200 // lt2.EPOCH_REPORTS + 1):
            ve = KEYS.view(e)
            for n in range(e * lt2.EPOCH_REPORTS, (e + 1) * lt2.EPOCH_REPORTS):
                for j in lt2.J_ON_AIR:
                    _N_OF[lt2.report_tag(ve, n, j)] = n
    return _N_OF.get(bytes(tag))


def owner_sk(tag: bytes) -> bytes:
    """sk_n of the report this tag belongs to: what the owner's app signs every 4244 event for it with."""
    n = n_of(tag)
    assert n is not None, "not a test report tag"
    return lt2.mailbox_sk(KEYS.cmd(lt2.epoch(n)), n)


def anchor_event(n, created_at=T0 - 3600, exp=None, sk=None, extra=()):
    ve = KEYS.view(lt2.epoch(n))
    tags = [["v", "2"]] + [["y", lt2.report_tag(ve, n, j).hex()] for j in lt2.J_ON_AIR] + \
        [["expiration", str(exp or created_at + 26 * 3600)]] + list(extra)
    return sign_event(sk or lt2.mailbox_sk(KEYS.cmd(lt2.epoch(n)), n), created_at, lt2.NOSTR_COMMAND_KIND, tags, "")


def make(radio=None, now=T0, ledger=None, key=None, auto_anchor=True, **lt):
    """A bridge with fast timings. The tests of other topics (routes, claims, limits, groups) predate the
    signed mailbox: with auto_anchor the owner's anchor for a test report reaches the bridge just before
    the radio hears it, and anchor_lead_s is 0. The signed mailbox has its own tests (test_signed_mailbox.py)."""
    cfg = Config()
    if key is not None:
        cfg.bridge.key = key.hex()
    cfg.libretag.publish_delay_max_s = 0.01
    cfg.libretag.claim_wait_s = 0.02
    cfg.libretag.anchor_lead_s = 0
    for a, v in lt.items():
        setattr(cfg.libretag, a, v)
    clock = {"t": float(now)}
    br = Bridge(cfg, radio or MemoryDirectRadio(), Pool(), ledger or Ledger(), clock=lambda: clock["t"],
                rng=random.Random(1))
    br.clock_ref = clock
    if auto_anchor:
        real = br.on_datagram

        async def on_datagram(dg):
            p = bytes(dg.payload)
            n = n_of(p[:4]) if len(p) == lt2.REPORT_P_LEN else None
            if n is not None:
                await br.on_relay_event(anchor_event(n, created_at=int(clock["t"]) - 10).to_dict(), "wss://anchor")
            return await real(dg)

        br.on_datagram = on_datagram
    return br


def run(coro):
    return asyncio.run(coro)


def mailbox_event(pc, ys, route="near", created_at=T0 - 100, exp=None, sk=None):
    """Signed by the owner's sk_n (of ys[0]) unless sk is given. Format 1 ignored the route and flooded
    when the report was not heard directly; these tests keep that behaviour, so "near" becomes "flood"
    (binding routes are tested in test_bridge_market.py)."""
    route = "flood" if route == "near" else route
    tags = [["v", "2"]] + [["y", y.hex()] for y in ys] + [["route", route],
                                                          ["expiration", str(exp or T0 + 48 * 3600)]]
    return sign_event(sk or owner_sk(ys[0]), created_at, lt2.NOSTR_COMMAND_KIND, tags, base64.b64encode(pc).decode())


def heard(p, hops=0, path=None, rx_time=None):
    if path is None and hops:
        path = [bytes([0x20 + i]) for i in range(hops)]
    return Datagram(*LT, p, hops, 8.0, path=path if hops else [], rx_time=rx_time)


def lt_published(br):
    return [e for e in br.pool.published if e["kind"] == lt2.NOSTR_REPORT_KIND]


# ---------------------------------------------------------------- uplink (kind 4243)


def test_report_uplink_variant_b_fresh_key_once():
    p5, p6 = report(5), report(6)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p5, 2))
        await br.on_datagram(heard(p5, 1))  # another flood copy
        await br.on_datagram(heard(p6, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    p5 = report(5)
    evs = lt_published(br)
    assert len(evs) == 2 and br.stats.c["lt_rx_duplicate"] == 1
    evs.sort(key=lambda e: base64.b64decode(e["content"]) != p5)
    e = Event.from_dict(evs[0])
    assert e.verify() and e.tags[0] == ["y", p5[:4].hex()]
    assert e.tags[1][0] == "expiration" and int(e.tags[1][1]) == e.created_at + 14 * 86400
    assert base64.b64decode(e.content) == p5
    assert evs[0]["pubkey"] != evs[1]["pubkey"]  # fresh random key per event
    assert br.ledger.get(lt2svc.report_key(p5))["published_at"]


def test_uplink_dedup_needs_same_y_and_content_on_enough_relays():
    p, other = report(7), report(8)
    junk = p[:4] + bytes(41)  # stolen tag, other content

    def on_relays(br, payload, urls):
        ev = sign_event(k(2), T0, lt2.NOSTR_REPORT_KIND, [["y", payload[:4].hex()]],
                        base64.b64encode(payload).decode(), bytes(32))
        for u in urls:
            br.lt2.on_relay_event(ev, u, T0)

    async def go():
        br = make()
        await br.start()
        on_relays(br, p, ["wss://a", "wss://b"])     # already on 2 relays: stay quiet
        on_relays(br, junk, ["wss://a", "wss://b"])  # same y, other content: does not count
        on_relays(br, other, ["wss://a"])            # only on one relay: publish anyway
        await br.on_datagram(heard(p))
        await br.on_datagram(heard(other))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [base64.b64decode(e["content"]) for e in lt_published(br)] == [other]
    assert br.stats.c["lt_up_dedup"] == 1


def test_uplink_dedup_waits_for_events_arriving_during_delay():
    p = report(9)

    async def go():
        br = make(publish_delay_max_s=0.2)
        await br.start()
        await br.on_datagram(heard(p))
        ev = sign_event(k(3), T0, lt2.NOSTR_REPORT_KIND, [["y", p[:4].hex()]], base64.b64encode(p).decode())
        await asyncio.sleep(0.01)
        for u in ("wss://a", "wss://b"):
            await br.on_relay_event(ev.to_dict(), u)
        await br.drain()
        await br.stop()
        return br

    assert lt_published(run(go())) == []


# ---------------------------------------------------------------- mailbox (kind 4244)


def test_mailbox_known_before_rule_and_one_send_per_x():
    p, pc = report(10), command(10)
    late_p, late_pc = report(11), command(11)
    old_p, old_pc = report(12), command(12)

    async def go():
        br = make()
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4], report(10, 1)[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.on_datagram(heard(p, 0))  # same report again: no second downlink
        await br.drain()
        # a command from the locked key learnt after its report: a late entry, sent at once (PROTOCOL.md 4.2)
        await br.on_datagram(heard(late_p, 0))
        await br.on_relay_event(mailbox_event(late_pc, [late_p[:4]]).to_dict(), "wss://a")
        await br.on_datagram(heard(late_p, 0))
        await br.drain()
        # ... but not once the report is more than late_max_s old
        await br.on_datagram(heard(old_p, 0))
        br.clock_ref["t"] += 61
        await br.on_relay_event(mailbox_event(old_pc, [old_p[:4]]).to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc, late_pc]
    assert br.stats.c["lt_cmd_late"] == 1 and br.stats.c["lt_cmd_late_too_late"] == 1, dict(br.stats.c)
    assert br.radio.sent[0].path == []  # zero-hop
    assert br.stats.c["lt_cmd_sent_zero-hop"] == 2


@pytest.mark.parametrize("hops,route,radio_cls,expect", [
    (0, "near", MemoryDirectRadio, ("zero-hop", [])),   # heard directly: zero-hop reaches it
    (1, "near", MemoryDirectRadio, ("flood", None)),     # otherwise flood (0.7.3), never along a path
    (3, "near", MemoryDirectRadio, ("flood", None)),
    (20, "near", MemoryDirectRadio, ("flood", None)),    # no hop limit any more
    (None, "near", MemoryDirectRadio, ("flood", None)),
    (0, "near", MemoryRadio, ("flood", None)),           # adapter cannot send zero-hop
    (2, "near", MemoryRadio, ("flood", None)),
    (5, "flood", MemoryDirectRadio, ("flood", None)),
    (0, "flood", MemoryDirectRadio, ("zero-hop", [])),
])
def test_mailbox_route_rules(hops, route, radio_cls, expect):
    p, pc = report(20), command(20)
    dg = heard(p, hops or 0) if hops is not None else Datagram(*LT, p, None)

    async def go():
        br = make(radio=radio_cls())
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]], route).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(dg)
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    how, path = expect
    assert len(br.radio.sent) == 1 and br.radio.sent[0].payload == pc
    assert br.radio.sent[0].path == path and br.stats.c[f"lt_cmd_sent_{how}"] == 1


def test_mailbox_skips_stale_or_replayed_report_and_command_heard_on_air():
    p, pc = report(30), command(30)
    q, qc = report(31), command(31)

    async def go():
        br = make()
        await br.start()
        for pp, cc in ((p, pc), (q, qc)):
            await br.on_relay_event(mailbox_event(cc, [pp[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 100
        await br.on_datagram(heard(p, 0, rx_time=T0 + 50))  # replayed from a radio queue, 50 s old
        await br.on_datagram(Datagram(*LT, qc, 0))            # another bridge already delivered qc
        await br.on_datagram(heard(q, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == []
    assert br.stats.c["lt_cmd_report_stale"] == 1 and br.stats.c["lt_cmd_heard_on_air"] == 1


def test_mailbox_validation_expiration_cap_and_size_cap():
    p = report(40)

    async def go():
        br = make(mailbox_cap=3, mailbox_relay_share=1.0)
        await br.start()
        bad = [
            sign_event(k(1), T0, 4244, [["y", p[:4].hex()]], base64.b64encode(b"x" * 28).decode()),  # length
            sign_event(k(1), T0, 4244, [["y", "XYZ"]], base64.b64encode(command(40)).decode()),          # tag
            mailbox_event(command(40), [p[:4]], exp=T0 - 1),                                            # expired
        ]
        forged = mailbox_event(command(40), [p[:4]]).to_dict()
        forged["content"] = base64.b64encode(command(41)).decode()
        for ev in bad:
            await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.on_relay_event(forged, "wss://a")
        far = mailbox_event(command(40), [p[:4]], exp=T0 + 30 * 86400)
        await br.on_relay_event(far.to_dict(), "wss://a")
        for i in range(4):
            await br.on_relay_event(mailbox_event(command(50 + i), [report(50 + i)[:4]], created_at=T0 + i).to_dict(),
                                    "wss://a")
        await br.stop()
        return br, far

    br, far = run(go())
    assert br.stats.c["lt_mailbox_bad"] == 2 and br.stats.c["lt_mailbox_expired"] == 1
    assert br.stats.c["lt_mailbox_bad_signature"] == 1
    assert br.ledger.mailbox_size() == 3  # capped
    rows = br.ledger._all("SELECT expires FROM mailbox WHERE event_id=?", (far.id,))
    assert rows == [] or rows[0][0] <= T0 + 48 * 3600


def test_mailbox_event_turned_down_once_counted_once():
    """Every relay reconnect resends the mailbox events of the last 48 h: a junk event (another app using
    kind 4244) and a timed entry whose `at` has passed were counted again each time (test bridge, 2026-11-18:
    lt_mailbox_bad +6 per relay reconnect). A forged copy under a real event's id does not shadow it."""
    p = report(60)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        now = int(br.clock_ref["t"]) + 600
        br.clock_ref["t"] = now
        junk = sign_event(k(1), now - 3600, 4244, [], base64.b64encode(b'{"username":"x"}').decode())
        late = timed_event(command(61), xc(7), now - 61, p[:4], created_at=now - 100)
        good = mailbox_event(command(62), [report(62)[:4]], created_at=now - 10)
        forged = dict(good.to_dict(), content=base64.b64encode(b"y" * 5).decode())
        for _ in range(3):  # three reconnects
            for ev in (junk.to_dict(), late.to_dict(), forged, good.to_dict()):
                await br.on_relay_event(ev, "wss://a")
        await br.stop()
        return br, good

    br, good = run(go())
    assert br.stats.c["lt_mailbox_bad"] == 2  # junk and the forged copy, once each
    assert br.stats.c["lt_mailbox_timed_late"] == 1
    assert br.stats.c["lt_mailbox_added"] == 1 and br.ledger.mailbox_pending(good.id)


def test_mailbox_persists_across_restart(tmp_path):
    p, pc = report(60), command(60)
    path = tmp_path / "ledger.sqlite"

    async def first():
        br = make(ledger=Ledger(path))
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        await br.stop()

    async def second():
        br = make(ledger=Ledger(path), now=T0 + 60)
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    run(first())
    br = run(second())
    assert [d.payload for d in br.radio.sent] == [pc]
    assert Ledger(path).mailbox_sent(mailbox_event(pc, [p[:4]]).id)


# ---------------------------------------------------------------- claims (kind 24244)


def claim(sk, y, hops, created_at=T0):
    return sign_event(sk, created_at, lt2.NOSTR_CLAIM_KIND, [["y", y.hex()], ["hops", str(hops)]], "")


def test_claim_election_fewest_hops_then_lowest_hash(monkeypatch):
    p, pc = report(70), command(70)
    x = p[:4]
    mine_sk = k(100)
    monkeypatch.setattr(lt2svc, "_fresh_key", lambda: mine_sk)
    mine = lt2svc.claim_score(secp.pubkey(mine_sk).hex(), x)
    lower = next(k(i) for i in range(200, 400) if lt2svc.claim_score(secp.pubkey(k(i)).hex(), x) < mine)
    higher = next(k(i) for i in range(200, 400) if lt2svc.claim_score(secp.pubkey(k(i)).hex(), x) > mine)

    async def scenario(rival, peers=True):
        # since 2026-11-18 only claims from known bridges count (PROTOCOL.md 4.1): the rivals are peers
        br = make(peers=[secp.pubkey(lower).hex(), secp.pubkey(higher).hex()] if peers else [])
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [x]).to_dict(), "wss://a")
        if rival:
            await br.on_relay_event(rival.to_dict(), "wss://b")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 1))
        await br.drain()
        await br.stop()
        return br

    alone = run(scenario(None))
    claims = [e for e in alone.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND]
    assert len(alone.radio.sent) == 1 and len(claims) == 1
    assert claims[0]["tags"] == [["y", x.hex()], ["hops", "1"]] and claims[0]["content"] == ""
    assert len(run(scenario(claim(lower, x, 0))).radio.sent) == 0    # rival heard it directly
    assert len(run(scenario(claim(higher, x, 3))).radio.sent) == 1   # rival further away
    assert len(run(scenario(claim(lower, x, 1))).radio.sent) == 0    # tie on hops, rival hash lower
    lost = run(scenario(claim(lower, x, 1)))
    assert lost.stats.c["lt_cmd_lost_claim"] == 1
    assert len(run(scenario(claim(higher, x, 1))).radio.sent) == 1   # tie on hops, our hash lower
    assert len(run(scenario(claim(lower, x, 0, created_at=T0 - 300))).radio.sent) == 1  # stale claim ignored
    jam = run(scenario(claim(lower, x, 0), peers=False))  # an unknown key with hops 0 no longer wins
    assert len(jam.radio.sent) == 1 and jam.stats.c["lt_claim_ignored"] == 1


def test_two_bridges_over_shared_relay_one_transmits():
    p, pc = report(80), command(80)

    class SharedPool(Pool):
        def __init__(self, hub):
            super().__init__()
            self.hub = hub

        async def publish(self, ev, retry=True):
            self.published.append(ev)
            for br in self.hub:
                await br.on_relay_event(ev, "wss://hub")
            return {"wss://hub": (True, "")}

    async def go():
        hub, ether = [], Ether()
        # each bridge has its own key and announces it; b proves it heard the report (PROTOCOL.md 4.1)
        a, b = make(radio=MemoryDirectRadio(ether), key=k(901)), make(radio=MemoryDirectRadio(ether), key=k(902))
        for br in (a, b):
            br.pool = SharedPool(hub)
            hub.append(br)
            await br.start()
            await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://hub")
            br.clock_ref["t"] += 1
        await asyncio.gather(a.on_datagram(heard(p, 2)), b.on_datagram(heard(p, 1)))
        await a.drain()
        await b.drain()
        for br in (a, b):
            await br.stop()
        return a, b

    a, b = run(go())
    assert a.radio.sent == [] and [d.payload for d in b.radio.sent] == [pc]  # b heard it with fewer hops
    assert a.stats.c["lt_cmd_lost_claim"] == 1


@pytest.mark.timeout(5)
def test_drain_returns_when_a_task_finished_before_its_done_callback_ran():
    # A finished task leaves br.tasks through a done callback that runs one loop iteration later;
    # gather() over finished tasks does not yield (Python 3.12+), so drain() must yield itself.
    async def go():
        br = make()

        async def nothing():
            pass

        t = br._spawn(nothing())
        await asyncio.sleep(0)  # t runs to completion; its tasks.discard is queued behind us
        assert t.done() and t in br.tasks
        await br.drain()
        assert not br.tasks

    run(go())


# ---------------------------------------------------------------- airtime budget


def test_airtime_budget_silences_bridge():
    async def go():
        br = make(rx_per_first_hop_per_min=100)  # 20 direct reports in 20 s
        br.cfg.airtime.budget_pct = 0.1  # 3.6 s per hour
        br.airtime.cap_s = 3.6
        await br.start()
        one = br.airtime_s(29)
        sent = 0
        for i in range(20):
            p, pc = report(100 + i), command(100 + i)
            await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
            br.clock_ref["t"] += 1
            await br.on_datagram(heard(p, 0))
            await br.drain()
        sent = len(br.radio.sent)
        br.clock_ref["t"] += 3600  # the window rolls on
        p, pc = report(200), command(200)
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br, one, sent

    br, one, sent = run(go())
    assert 0.17 < one < 0.19  # 37 B zero-hop at SF7/62.5 kHz/CR5
    assert sent == int(3.6 // one) and br.stats.c["tx_airtime_exhausted"] == 20 - sent
    assert len(br.radio.sent) == sent + 1


# ---------------------------------------------------------------- Local API capabilities


class FakeWs:
    """Answers channel.sendData synchronously; records params."""

    def __init__(self, radio, error=None):
        self.radio, self.sent, self.error = radio, [], error

    def send(self, raw):
        m = json.loads(raw)
        self.sent.append(m["params"])
        reply = {"id": m["id"], "ok": self.error is None}
        if self.error:
            reply["error"] = {"code": self.error}
        self.radio.client._handle(reply)


def local_api(caps, region=None):
    import tempfile
    from pathlib import Path
    from nostrmesh.localapi import Identity
    ident = Identity(Path(tempfile.mkdtemp()) / "id.json")
    r = LocalApiRadio("ws://127.0.0.1:8787/api/v1", {"#libretag": [0xFF4C]}, identity=ident, region=region)
    got = []
    r._on_rx = got.append
    r.client._ws = FakeWs(r)
    r.client._handle({"type": "hello", "capabilities": caps, "device": {"connected": False}})
    return r, got


def test_local_api_without_path_capability_falls_back():
    r, got = local_api(["airtime"])
    assert not r.supports_direct
    with pytest.raises(RadioError):
        r.send_direct("#libretag", 0xFF4C, b"x", [])
    r._event({"type": "channel.data", "channel": "#libretag", "dataType": 0xFF4C, "payload": "eHg=",
              "hops": 2, "path": "a1b2", "pathHashSize": 1, "rssiDbm": -90, "routeType": "flood"})
    assert got[0].hops == 2 and got[0].path is None and got[0].rssi is None  # not advertised: ignored
    r.send("#libretag", 0xFF4C, b"x")
    assert "path" not in r.client._ws.sent[-1]


def test_local_api_with_path_capabilities():
    r, got = local_api(["tx_path_data", "rx_path", "rx_meta", "duty_cap"])
    assert r.supports_direct
    r._event({"type": "channel.data", "channel": "#libretag", "dataType": 0xFF4C, "payload": "eHg=",
              "hops": 2, "path": "a1b2c3d4", "pathHashSize": 2, "snrDb": 6.5, "rssiDbm": -97, "routeType": "flood",
              "receivedAt": "2026-11-16T10:00:00Z"})
    dg = got[0]
    assert dg.path == [b"\xa1\xb2", b"\xc3\xd4"] and dg.path_hash_size == 2 and dg.hops == 2
    assert (dg.snr, dg.rssi, dg.route_type) == (6.5, -97, "flood") and dg.rx_time == 1794823200.0
    r.send_direct("#libretag", 0xFF4C, b"x", [])
    assert r.client._ws.sent[-1]["path"] == "" and r.client._ws.sent[-1]["pathHashSize"] == 1  # zero-hop
    r.send_direct("#libretag", 0xFF4C, b"x", [b"\xc3\xd4", b"\xa1\xb2"], 2)
    assert r.client._ws.sent[-1]["path"] == "c3d4a1b2" and r.client._ws.sent[-1]["pathHashSize"] == 2
    r.send("#libretag", 0xFF4C, b"x")
    assert "path" not in r.client._ws.sent[-1]  # flood
    r.client._ws.error = "budget"
    from nostrmesh.localapi import BudgetExceeded
    with pytest.raises(BudgetExceeded):
        r.send("#libretag", 0xFF4C, b"x")


def test_local_api_region_only_with_the_regions_capability():
    r, _ = local_api(["tx_path_data"], region="sk")
    r.send("#libretag", 0xFF4C, b"x")
    assert "region" not in r.client._ws.sent[-1]  # the server cannot scope: its default scope
    r, _ = local_api(["regions"], region="sk")
    r.send("#libretag", 0xFF4C, b"x")
    assert [p for p in r.client._ws.sent if "payload" in p][-1]["region"] == "sk"
    with pytest.raises(RadioError):
        r.send_direct("#libretag", 0xFF4C, b"x", [])  # no tx_path_data: nothing goes out
    assert len([p for p in r.client._ws.sent if "payload" in p]) == 1


def test_bridge_over_local_api_without_tx_path_floods():
    p, pc = report(90), command(90)
    r, _ = local_api(["rx_path", "rx_meta"])

    async def go():
        br = make(radio=r)
        br.radio.start = lambda on_rx: None
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert len(r.client._ws.sent) == 1 and "path" not in r.client._ws.sent[0] and br.stats.c["lt_cmd_sent_flood"] == 1


# ---------------------------------------------------------------- timed entries (0.7 checks)


def timed_event(pc, xc, at, via, route="near", sk=None, created_at=T0):
    """Signed by the owner's sk_n of report `via` unless sk is given; "near" becomes "flood" (see mailbox_event)."""
    route = "flood" if route == "near" else route
    tags = [["v", "2"], ["y", xc.hex()], ["route", route], ["expiration", str(created_at + 48 * 3600)],
            ["at", str(at)], ["via", via.hex()]]
    return sign_event(sk or owner_sk(via), created_at, lt2.NOSTR_COMMAND_KIND, tags, base64.b64encode(pc).decode())


def xc(i):
    return k(1000 + i)[:4]  # stands in for HMAC(C_e(n), "LT2-cx" || n || k)[0:4]


def claims_of(br):
    return [e for e in br.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND]


def test_timed_entry_sent_at_time_by_flood():
    p, pc = report(300), command(300)

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p, 2))      # report n via two repeaters: path stored by x
        await br.on_datagram(heard(p, 3))      # a longer flood copy does not replace it
        await br.drain()
        br.clock_ref["t"] += 3600              # an hour later, a check
        at = int(br.clock_ref["t"]) + 120
        await br.on_relay_event(timed_event(pc, xc(1), at, p[:4]).to_dict(), "wss://a")
        br.clock_ref["t"] = at - 10
        br.lt2.timed_tick(br.clock_ref["t"])   # too early: not even the claim
        await br.drain()
        early = (list(br.radio.sent), len(claims_of(br)))
        br.clock_ref["t"] = at - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        br.lt2.timed_tick(br.clock_ref["t"])   # once only
        await br.drain()
        await br.stop()
        return br, early, at

    br, early, at = run(go())
    assert early == ([], 0)
    assert [d.payload for d in br.radio.sent] == [pc]
    assert br.radio.sent[0].path is None  # flood: the tracker may have moved since report n
    c = claims_of(br)
    assert len(c) == 1 and c[0]["tags"] == [["y", xc(1).hex()], ["hops", "2"]] and c[0]["created_at"] < at
    assert br.stats.c["lt_cmd_timed_sent"] == 1 and br.stats.c["lt_cmd_sent_flood"] == 1
    assert br.stats.c["lt_mailbox_added_timed"] == 1


@pytest.mark.parametrize("hops,route,radio_cls,expect", [
    (0, "near", MemoryDirectRadio, ("zero-hop", [])),
    (3, "near", MemoryDirectRadio, ("flood", None)),
    (20, "near", MemoryDirectRadio, ("flood", None)),
    (None, "near", MemoryDirectRadio, ("flood", None)),
    (0, "near", MemoryRadio, ("flood", None)),
    (2, "near", MemoryRadio, ("flood", None)),
    (0, "flood", MemoryDirectRadio, ("zero-hop", [])),
])
def test_timed_route_rules(hops, route, radio_cls, expect):
    p, pc = report(310), command(310)
    dg = heard(p, hops or 0) if hops is not None else Datagram(*LT, p, None)

    async def go():
        br = make(radio=radio_cls(), timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(dg)
        await br.drain()
        at = int(br.clock_ref["t"]) + 600
        await br.on_relay_event(timed_event(pc, xc(2), at, p[:4], route).to_dict(), "wss://a")
        br.clock_ref["t"] = at - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    how, path = expect
    assert [d.payload for d in br.radio.sent] == [pc]
    assert br.radio.sent[0].path == path and br.stats.c[f"lt_cmd_sent_{how}"] == 1


def test_timed_without_stored_path_sends_nothing():
    p, q = report(320), report(321)

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(q, 0))
        await br.drain()
        br.clock_ref["t"] += 49 * 3600             # q's path is older than 48 h
        br.lt2.prune(br.clock_ref["t"])
        now = int(br.clock_ref["t"])
        await br.on_relay_event(timed_event(command(320), xc(3), now + 60, p[:4],
                                            created_at=now).to_dict(), "wss://a")  # p never heard
        await br.on_relay_event(timed_event(command(321), xc(4), now + 60, q[:4],
                                            created_at=now).to_dict(), "wss://a")
        br.clock_ref["t"] = now + 60 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and claims_of(br) == []
    assert br.stats.c["lt_cmd_timed_no_path"] == 2
    assert br.ledger._all("SELECT COUNT(*) FROM report_paths") == [(0,)]


def test_timed_entry_too_late_dropped_slightly_late_sent_at_once():
    p, pc = report(330), command(330)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        now = int(br.clock_ref["t"]) + 600
        br.clock_ref["t"] = now
        late = timed_event(command(331), xc(5), now - 61, p[:4], created_at=now - 100)
        await br.on_relay_event(late.to_dict(), "wss://a")
        await br.on_relay_event(timed_event(pc, xc(6), now - 30, p[:4], created_at=now - 100).to_dict(), "wss://a")
        br.lt2.timed_tick(now)
        await br.drain()
        await br.stop()
        return br, late

    br, late = run(go())
    assert br.stats.c["lt_mailbox_timed_late"] == 1
    assert br.ledger._all("SELECT 1 FROM mailbox WHERE event_id=?", (late.id,)) == []
    assert [d.payload for d in br.radio.sent] == [pc] and br.radio.sent[0].path == []


def test_timed_two_bridges_one_transmits():
    p, pc = report(340), command(340)

    class SharedPool(Pool):
        def __init__(self, hub):
            super().__init__()
            self.hub = hub

        async def publish(self, ev, retry=True):
            self.published.append(ev)
            for br in self.hub:
                await br.on_relay_event(ev, "wss://hub")
            return {"wss://hub": (True, "")}

    async def go():
        hub, ether = [], Ether()
        # timed claims count only between peers (a timed entry has no secret to prove hearing with)
        pa, pb = secp.pubkey(k(901)).hex(), secp.pubkey(k(902)).hex()
        a = make(radio=MemoryDirectRadio(ether), timed_claim_lead_s=0.05, key=k(901), peers=[pb])
        b = make(radio=MemoryDirectRadio(ether), timed_claim_lead_s=0.05, key=k(902), peers=[pa])
        at = T0 + 1800
        for br, hops in ((a, 2), (b, 1)):
            br.pool = SharedPool(hub)
            hub.append(br)
            await br.start()
            await br.on_datagram(heard(p, hops))
            await br.on_relay_event(timed_event(pc, xc(7), at, p[:4]).to_dict(), "wss://hub")
            br.clock_ref["t"] = at - 0.05
        a.lt2.timed_tick(a.clock_ref["t"])
        b.lt2.timed_tick(b.clock_ref["t"])
        await a.drain()
        await b.drain()
        for br in (a, b):
            await br.stop()
        return a, b

    a, b = run(go())
    assert a.radio.sent == [] and [d.payload for d in b.radio.sent] == [pc]  # b heard report n with fewer hops
    assert a.stats.c["lt_cmd_lost_claim"] == 1


def test_timed_siblings_survive_and_later_reports_still_answered():
    """Each check has its own entry: sending one leaves the next checks and the plain entries pending."""
    p, q = report(370), report(371)
    c1, c2, cq = command(370, 1), command(370, 2), command(371)

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p, 3))
        await br.drain()
        at1 = int(br.clock_ref["t"]) + 3600
        at2 = at1 + 3600
        for pc_, x_, at_ in ((c1, xc(11), at1), (c2, xc(12), at2)):
            await br.on_relay_event(timed_event(pc_, x_, at_, p[:4]).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(cq, [q[:4]], created_at=T0).to_dict(), "wss://a")
        br.clock_ref["t"] = at1 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        first = [d.payload for d in br.radio.sent]
        br.clock_ref["t"] = at2 - 0.05          # the tracker missed check 1: check 2 still goes
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(q, 1))       # and so does the plain entry for the next report
        await br.drain()
        await br.stop()
        return br, first

    br, first = run(go())
    assert first == [c1]
    assert [d.payload for d in br.radio.sent] == [c1, c2, cq]
    assert all(d.path is None for d in br.radio.sent)


def test_timed_entries_leave_06_behaviour_alone():
    p, pc = report(350), command(350)
    q, qc = report(351), command(351)

    async def go():
        br = make()
        await br.start()
        # a timed entry is never sent because a report with its y shows up
        await br.on_relay_event(timed_event(qc, q[:4], T0 + 7200, q[:4]).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(q, 0))
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc]

    async def off():
        br = make(timed_commands=False)
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.on_relay_event(timed_event(qc, xc(8), T0 + 600, p[:4]).to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(off())
    assert br.stats.c["lt_mailbox_timed_off"] == 1 and br.ledger.mailbox_size() == 0
    # the path is kept anyway: late entries (PROTOCOL.md 4.2) use it


def test_ledger_from_06_gains_timed_columns(tmp_path):
    import sqlite3
    path = tmp_path / "old.sqlite"
    db = sqlite3.connect(path)
    db.executescript("""CREATE TABLE mailbox (event_id TEXT NOT NULL, y TEXT NOT NULL, payload BLOB NOT NULL,
        route TEXT NOT NULL, created_at INTEGER NOT NULL, expires REAL NOT NULL, known_at REAL NOT NULL,
        sent_at REAL, PRIMARY KEY (event_id, y));""")
    db.execute("INSERT INTO mailbox VALUES ('e', 'aabbccdd', x'00', 'near', 1, 9e9, 1, NULL)")
    db.commit()
    db.close()
    led = Ledger(path)
    assert [e.event_id for e in led.mailbox_match("aabbccdd", 2, 3)] == ["e"]
    assert led.mailbox_add("f", ["11223344"], b"\x01", "near", 1, 9e9, 1, 100, at=50, via="55667788")
    assert [(e.event_id, e.at, e.via) for e in led.mailbox_timed_due(60, 10)] == [("f", 50, "55667788")]


# ---------------------------------------------------------------- cancelling (NIP-09 kind 5)


def deletion(sk, ids, created_at=T0, k_tag=True):
    tags = [["e", i] for i in ids] + ([["k", "4244"]] if k_tag else [])
    return sign_event(sk, created_at, 5, tags, "")


def test_deletion_subscription():
    br = make()
    subs = br.subscriptions()
    assert subs["nm-lt-del"] == [{"kinds": [5], "#k": ["4244"], "since": T0 - 48 * 3600}]


def test_cancel_drops_unsent_entry_by_same_key_only():
    p, pc = report(400), command(400)
    ev = mailbox_event(pc, [p[:4], report(400, 1)[:4]], sk=owner_sk(p[:4]))

    async def go(sk):
        br = make()
        await br.start()
        await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.on_relay_event(deletion(sk, [ev.id]).to_dict(), "wss://a")
        await br.on_relay_event(deletion(sk, [ev.id]).to_dict(), "wss://b")  # same deletion, second relay
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go(owner_sk(p[:4])))
    assert br.radio.sent == [] and br.ledger.mailbox_size() == 0
    assert br.stats.c["lt_mailbox_cancelled"] == 1
    other = run(go(k(42)))  # anyone else's deletion does nothing
    assert [d.payload for d in other.radio.sent] == [pc]
    assert other.stats.c["lt_mailbox_cancel_bad_key"] == 2 and other.stats.c["lt_mailbox_cancelled"] == 0


def test_cancel_timed_entry_no_claim_no_send():
    p, pc = report(410), command(410)
    ev = timed_event(pc, xc(10), T0 + 600, p[:4], sk=owner_sk(p[:4]))

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p, 1))
        await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.on_relay_event(deletion(owner_sk(p[:4]), [ev.id]).to_dict(), "wss://a")
        br.clock_ref["t"] = T0 + 600 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and claims_of(br) == []
    assert br.stats.c["lt_mailbox_cancelled"] == 1


def test_cancel_while_waiting_for_claim_or_at():
    p, pc = report(420), command(420)
    ev = timed_event(pc, xc(11), T0 + 600, p[:4], sk=owner_sk(p[:4]))

    async def go():
        br = make(timed_claim_lead_s=0.2)
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = T0 + 600 - 0.2
        br.lt2.timed_tick(br.clock_ref["t"])  # claim published, waiting for `at`
        await asyncio.sleep(0.05)
        await br.on_relay_event(deletion(owner_sk(p[:4]), [ev.id]).to_dict(), "wss://a")
        br.clock_ref["t"] = T0 + 600
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert len(claims_of(br)) == 1 and br.radio.sent == []
    assert br.stats.c["lt_cmd_cancelled_meanwhile"] == 1


def test_deletion_before_event_and_across_restart(tmp_path):
    p, pc = report(430), command(430)
    ev = mailbox_event(pc, [p[:4]], sk=owner_sk(p[:4]))
    path = tmp_path / "ledger.sqlite"

    async def first():
        br = make(ledger=Ledger(path))
        await br.start()
        await br.on_relay_event(deletion(owner_sk(p[:4]), [ev.id], k_tag=False).to_dict(), "wss://a")  # k is optional
        await br.stop()

    async def second():
        br = make(ledger=Ledger(path), now=T0 + 60)
        await br.start()
        await br.on_relay_event(ev.to_dict(), "wss://b")  # the event shows up later, from another relay
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    run(first())
    br = run(second())
    assert br.radio.sent == [] and br.ledger.mailbox_size() == 0
    assert br.stats.c["lt_mailbox_cancelled_before"] == 1


def test_cancel_after_transmission_only_logged_and_other_kinds_ignored():
    p, pc = report(440), command(440)
    ev = mailbox_event(pc, [p[:4]], sk=owner_sk(p[:4]))

    async def go():
        br = make()
        await br.start()
        await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.on_relay_event(deletion(owner_sk(p[:4]), [ev.id]).to_dict(), "wss://a")
        not_ours = sign_event(owner_sk(p[:4]), T0, 5, [["e", ev.id], ["k", "1"]], "")  # a deletion of other kinds
        forged = deletion(owner_sk(p[:4]), [ev.id]).to_dict()
        forged["sig"] = "00" * 64
        await br.on_relay_event(not_ours.to_dict(), "wss://a")
        await br.on_relay_event(forged, "wss://a")
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc]
    assert br.stats.c["lt_mailbox_cancel_too_late"] == 1 and br.stats.c["lt_mailbox_cancelled"] == 0
    assert br.ledger.mailbox_sent(ev.id)


def test_ble_console_command_goes_out_like_any_other():
    """BLE_CONSOLE (0x06, firmware 0.9.2) is an ordinary sealed command to a bridge: it opens nothing
    and floods it as the route allows (the owner's own radio never floods it, but that is the app's
    rule, not the mailbox's)."""
    p = report(40)
    pc = lt2.seal_command(KEYS.cmd(lt2.epoch(40)), 40, Command.ble_console(9))
    q = report(41)
    qk = lt2.seal_command(KEYS.cmd(lt2.epoch(41)), 41, Command.ble_console(10, 300), 2)

    async def go():
        br = make(radio=MemoryDirectRadio(), timed_claim_lead_s=0.05)
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]], "flood").to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 2))   # heard over two hops: flood
        await br.on_datagram(heard(q, 3))
        await br.drain()
        br.clock_ref["t"] += 3600
        at = int(br.clock_ref["t"]) + 120
        await br.on_relay_event(timed_event(qk, xc(40), at, q[:4], route="flood").to_dict(), "wss://a")
        br.clock_ref["t"] = at - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc, qk]
    assert all(d.path is None for d in br.radio.sent)
    assert br.stats.c["lt_cmd_sent_flood"] == 2

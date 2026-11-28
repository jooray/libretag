"""The Python bridge against the quiet mesh contract (docs/design/quiet-mesh.md, "Python bridge"; PROTOCOL.md 3.3,
3.4, 4.3; SPEC 3.2 "Bridges", "Radio health"), on the shared vectors docs/vectors/quiet-mesh-v1.json."""
import asyncio
import base64
import json
from pathlib import Path

import pytest

from libretag import lt2
from libretag import quietmesh as q
from libretag.lt2 import Command, epoch
from libretag.meshcore import Packet
from nostrmesh import market, secp
from nostrmesh.localapi import datagram_from_event
from nostrmesh.ledger import Ledger
from nostrmesh.nostr import Event, sign_event
from nostrmesh.radio import Datagram, KissRadio, MemoryDirectRadio, MemoryRadio, RadioError
from test_lt2bridge import KEYS, LT, T0, command, heard, k, make, mailbox_event, owner_sk, report, run

V = json.loads((Path(__file__).resolve().parents[2] / "docs" / "vectors" / "quiet-mesh-v1.json").read_text())
CE300 = KEYS.cmd(epoch(300))
ME = k(1)
G = "0123456789abcdef"
P2 = [bytes.fromhex("a5e1"), bytes.fromhex("b7d2")]  # the vectors' path of two repeaters, hash size 2


def flood(p, path, hs=2, route="tflood"):
    return Datagram(*LT, p, len(path), 8.0, path=list(path), path_hash_size=hs, route_type=route)


def direct(p):
    return Datagram(*LT, p, None, 8.0, path=[], route_type="direct")


def ack_event(content: bytes, y: bytes, route: str, sk=None, at=None, vias=(), p=None, created_at=None, extra=()):
    tags = [["v", "2"], ["a", "1"], ["y", y.hex()], ["route", route],
            ["expiration", str((created_at or T0) + 600 if at is None else at + 120)]]
    if at is not None:
        tags.append(["at", str(at)])
    tags += [["via", v.hex()] for v in vias] + ([["p", p]] if p else []) + list(extra)
    return sign_event(sk or owner_sk(vias[0] if vias else y), created_at or T0, lt2.NOSTR_COMMAND_KIND, tags,
                      base64.b64encode(content).decode())


def ack_content(n, form, k_=0, hold=4, more=False):
    return base64.b64decode(q.ack_entry_content(KEYS.cmd(epoch(n)), n, k_, form, hold, more))


def notes(br, what):
    return [e for e in br.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND and any(t[0] == what for t in e["tags"])]


def lt_sent(br, dtype=0xFF4C):
    return [d for d in br.radio.sent if d.data_type == dtype]


# ---------------------------------------------------------------- arrivals (R36, P5)


def test_local_api_event_route_types_and_hop_counts():
    caps = {"rx_meta", "rx_path", "rx_scope", "rx_queued"}
    base = {"type": "channel.data", "channel": "#libretag", "dataType": 0xFF4C, "payload": "eHg="}
    dg = datagram_from_event({**base, "routeType": "direct", "hops": 0, "path": ""}, b"xx", caps)
    assert dg.hops is None and dg.route == "direct" and dg.heard_direct  # DIRECT: unknown hop count
    dg = datagram_from_event({**base, "routeType": "flood", "path": "", "hops": 0}, b"xx", caps)
    assert dg.hops == 0 and dg.heard_direct
    dg = datagram_from_event({**base, "routeType": "tflood", "path": "a10b", "pathHashSize": 1,
                              "transportCode": 4242, "scope": "sk-test", "queued": True}, b"xx", caps)
    assert (dg.hops, dg.path, dg.route, dg.transport_code, dg.scope, dg.queued) == \
        (2, [b"\xa1", b"\x0b"], "tflood", 4242, "sk-test", True) and not dg.heard_direct
    dg = datagram_from_event({**base, "routeType": "tdirect"}, b"xx", caps)
    assert dg.route == "tdirect" and dg.hops is None
    dg = datagram_from_event({**base, "routeType": "tflood", "queued": True, "transportCode": 1}, b"xx", {"rx_meta"})
    assert not dg.queued and dg.transport_code is None  # not advertised: ignored


def test_kiss_keeps_direct_route_and_transport_code_of_the_vectors():
    kiss = KissRadio("x", ["#libretag"], link=object())
    for c in V["copies"][0]["copies"]:
        dg = kiss.handle_raw(bytes.fromhex(c["packet"]))
        if c["j"] == 11:
            assert dg is None  # DIRECT with path entries left: on its way, not for us yet
            continue
        assert dg is not None and dg.payload.hex() == c["p"]
        if c["j"] == 9:
            assert dg.route == "direct" and dg.hops is None and dg.heard_direct
        elif c["scope"]:
            assert dg.route == "tflood" and dg.transport_code is not None
        else:
            assert dg.route == "flood" and dg.hops == 0


def test_reply_scope_from_the_transport_code():
    """A scoped flood is answered in its own scope: the bridge tests its regions against code[0]."""
    c0 = next(c for c in V["copies"][0]["copies"] if c["j"] == 0)      # copy 0, scope sk-test
    kiss = KissRadio("x", ["#libretag"], link=object())
    dg = kiss.handle_raw(bytes.fromhex(c0["packet"]))
    dg.hops, dg.path = 3, [b"\x01\x02"] * 3                              # pretend it came over 3 repeaters
    p = dg.payload
    n = V["copies"][0]["n"]

    async def go():
        br = make()
        br.cfg.radio.regions = ["sk-test", "sk"]
        await br.start()
        await br.on_relay_event(mailbox_event(command(n), [p[:4]], "flood", binding=True).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(dg)
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [(d.payload, d.path, d.scope) for d in br.radio.sent] == [(command(n), None, "sk-test")]


# ---------------------------------------------------------------- reports (R21, R22, fast publish)


def test_fast_publish_for_direct_hearings_and_slow_with_a_command_waiting():
    br = make()
    br.cfg.libretag.fast_publish_min_s, br.cfg.libretag.fast_publish_max_s = 0.3, 0.8
    br.cfg.libretag.publish_delay_min_s, br.cfg.libretag.publish_delay_max_s = 2.0, 5.0
    assert all(0.3 <= br.lt2._publish_delay(True) <= 0.8 for _ in range(50))
    assert all(2.0 <= br.lt2._publish_delay(False) <= 5.0 for _ in range(50))


def test_report_in_the_outbox_at_hearing_time_survives_a_crash(tmp_path):
    p = report(620)
    path = tmp_path / "ledger.sqlite"

    async def crash():
        br = make(ledger=Ledger(path), publish_delay_min_s=30, publish_delay_max_s=30)
        await br.start()
        await br.on_datagram(heard(p, 3))   # slow delay: the bridge dies before it publishes
        assert br.ledger.outbox_size() == 1
        await br.stop()                     # cancels the publish task

    async def restart():
        br = make(ledger=Ledger(path))
        await br.start()
        br.clock_ref["t"] += 120
        await br.retry_outbox(br.clock_ref["t"])
        await br.on_datagram(heard(p, 3))   # the same report again: not first, nothing new
        await br.drain()
        await br.stop()
        return br

    run(crash())
    br = run(restart())
    pub = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_REPORT_KIND]
    assert len(pub) == 1 and pub[0]["created_at"] == T0 and br.ledger.outbox_size() == 0


def test_a_20_minute_old_replay_is_published_with_approx_and_locks_nothing():
    p, pc = report(630), command(630)

    async def go():
        br = make()
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0, rx_time=br.clock_ref["t"] - 1200))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    pub = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_REPORT_KIND]
    assert len(pub) == 1 and pub[0]["created_at"] == T0 + 1
    assert ["approx", str(T0 + 1 - 1200)] in pub[0]["tags"]
    assert br.radio.sent == [] and br.ledger.locked(p[:4].hex()) == set() and br.stats.c["lt_rx_stale"] == 1
    assert [e for e in br.pool.published if e["kind"] == market.RECEIPT_KIND] == []


def test_queued_frame_is_approx():
    p = report(631)

    async def go():
        br = make()
        await br.start()
        dg = heard(p, 0, rx_time=T0)
        dg.queued = True
        await br.on_datagram(dg)
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    pub = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_REPORT_KIND]
    assert any(t[0] == "approx" for t in pub[0]["tags"]) and br.stats.c["lt_lock"] == 0


# ---------------------------------------------------------------- ACK entries


def test_near_ack_entry_accepted_and_sent_zero_hop_with_a_note_even_without_open_entries():
    p = report(300, 9)  # a hello
    content = ack_content(300, "near")

    async def go():
        br = make(key=ME, accept_open=False)
        await br.start()
        await br.on_datagram(direct(p))
        await br.on_relay_event(ack_event(content, p[:4], "near").to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [(d.payload, d.path) for d in br.radio.sent] == [(content, [])]  # the 13 B near form, zero-hop
    assert bytes.fromhex(next(c for c in V["ack_entries"] if c["form"] == "near" and not c["more"])["content"]
                         .encode().hex()) and content == base64.b64decode(V["ack_entries"][0]["content"])
    n = notes(br, "ack")
    assert len(n) == 1 and ["ack", "1"] in n[0]["tags"] and ["y", p[:4].hex()] in n[0]["tags"]
    assert br.stats.c["lt_ack_sent_near"] == 1


def test_ack_refused_over_three_hops_from_an_unlocked_key_and_never_flooded():
    p = report(301, 0)

    async def go(dg, sk=None, route="direct", radio=None, content=None):
        br = make(radio=radio)
        await br.start()
        await br.on_datagram(dg)
        await br.on_relay_event(ack_event(content or ack_content(301, "both"), p[:4], route, sk=sk).to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    p3 = [b"\x01\x01", b"\x02\x02", b"\x03\x03"]
    assert run(go(flood(p, p3))).radio.sent == []                               # 3 hops: no ACK at all
    br = run(go(flood(p, P2[:1]), sk=k(99)))                                    # a stranger's key
    assert br.radio.sent == [] and br.stats.c["lt_mailbox_unsigned"] == 1
    br = run(go(flood(p, []), route="flood"))                                   # an ACK never floods
    assert br.radio.sent == [] and br.stats.c["lt_mailbox_bad"] == 1
    assert run(go(flood(p, []), radio=MemoryRadio())).radio.sent == []          # no zero-hop adapter: nothing


@pytest.mark.parametrize("hops", [1, 2])
def test_routed_ack_for_one_and_two_hops_matches_the_vectors(hops):
    n, path = 300, P2[:hops]
    vec = next(v for v in V["acks_routed"]["valid"] if v["n"] == n and v["k"] == 0 and len(v["path"]) == hops
               and v["hash_size"] == 2)
    p = report(n, 0)

    async def go():
        br = make(key=ME)
        await br.start()
        await br.on_datagram(flood(p, path))
        await br.on_relay_event(ack_event(ack_content(n, "both"), p[:4], "direct").to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert len(br.radio.sent) == 1
    d = br.radio.sent[0]
    assert d.payload.hex() == vec["datagram"] and d.path == list(reversed(path)) and d.path_hash_size == 2
    assert ["ack", "2"] in notes(br, "ack")[0]["tags"]
    assert ["via", p[:4].hex()] in notes(br, "ack")[0]["tags"]  # the path record: the flood copy itself
    claims = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND and any(t[0] == "hops" for t in e["tags"])]
    assert len(claims) == 1  # the first routed ACK of a flood: a claim round


def test_near_only_entry_for_a_moved_tag_sends_no_routed_form():
    p = report(302, 0)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(flood(p, P2[:1]))
        await br.on_relay_event(ack_event(ack_content(302, "near"), p[:4], "near").to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_ack_not_from_here"] == 1


def test_near_ack_skipped_when_heard_on_air():
    p = report(303, 9)
    content = ack_content(303, "near")

    async def go():
        br = make()
        br.cfg.libretag.ack_jitter_min_s = br.cfg.libretag.ack_jitter_max_s = 0.05
        await br.start()
        await br.on_datagram(direct(p))
        await br.on_relay_event(ack_event(content, p[:4], "near").to_dict(), "wss://a")
        await br.on_datagram(Datagram(*LT, content, None, route_type="direct"))  # the owner's node was faster
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_ack_dup_on_air"] == 1


def test_routed_ack_via_path_record_renewed_r33_day_three():
    """Day 1: flood over two repeaters, routed ACK. Day 2: copy 11 (DIRECT) with via = the day-1 x, a routed ACK
    along the stored path, which renews the record. Day 3 (49 h after the flood): another copy 11 with the same
    via still finds the path."""
    x0 = report(300, 0)

    async def go():
        br = make(key=ME)
        await br.start()
        await br.on_datagram(flood(x0, P2))
        await br.on_relay_event(ack_event(ack_content(300, "both"), x0[:4], "direct").to_dict(), "wss://a")
        await br.drain()
        out = []
        for day, n in ((1, 304), (2, 305)):
            br.clock_ref["t"] = T0 + 86400 * day + (3600 if day == 2 else 0)
            br.lt2.prune(br.clock_ref["t"])
            c11 = report(n, 11)
            await br.on_datagram(direct(c11))
            t = int(br.clock_ref["t"])
            await br.on_relay_event(ack_event(ack_content(n, "routed"), c11[:4], "direct", vias=[x0[:4]],
                                              sk=owner_sk(c11[:4]), created_at=t).to_dict(), "wss://a")
            await br.drain()
            out.append(list(br.radio.sent))
        await br.stop()
        return br, out

    br, out = run(go())
    assert len(out[0]) == 2 and len(out[1]) == 3
    for d, n in ((out[0][1], 304), (out[1][2], 305)):
        pa = ack_content(n, "routed")
        assert d.payload == q.ack_routed(pa, 2, P2) and d.path == list(reversed(P2))
    assert br.ledger.report_path(report(304, 11)[:4].hex(), 0).path == P2  # stored under the new x
    vias = [next(t[1] for t in e["tags"] if t[0] == "via") for e in notes(br, "ack")]
    assert vias == [x0[:4].hex()] * 3  # every "ack sent" note names the record whose path it used


# ---------------------------------------------------------------- commands: plan, fallback, groups (R34)


def test_bridge_plan_follows_the_plan_vectors():
    """Every `plan` vector through the bridge's planner (heard as given; via_path as a stored record)."""
    from nostrmesh.ledger import MailboxEntry, ReportPath
    br = make(radio=MemoryDirectRadio())
    for c in V["plan"]:
        args = c["args"]
        br.radio.supports_direct = args.get("can_zero_hop", True)
        br.radio.supports_path = args.get("can_path", True)
        br.cfg.libretag.direct_max_hops = args.get("max_hops", 2)
        kind = c["kind"]
        payload = bytes(13 if args.get("forms", "near") != "both" else 26) if kind == "ack" else bytes(29)
        e = MailboxEntry("e", "00" * 4, payload, c["route"], T0, T0 + 99, T0, fb=int(args.get("fb", True)),
                         blind=int(args.get("blind", False)), vias="11223344" if args.get("via_path") else "")
        if kind == "ack" and args.get("forms") == "routed":
            e.route = "direct"
        via_rp = ReportPath("11223344", 2, [bytes.fromhex(h) for h in args["via_path"]], 2, T0, "tflood") \
            if args.get("via_path") else None
        heard = c["heard"]
        pl = br.lt2._plan(kind, e, heard, "", 2, via_rp, blind=args.get("blind", False))
        want = c["plan"]
        if want["send"] == "none":
            assert pl is None, c
            continue
        assert pl is not None and pl.how == want["send"], c
        assert [h.hex() for h in (pl.route or [])] == want["path"], c
        assert pl.fallback == want["fallback"], c
        if kind == "ack":
            assert pl.form == want["form"], c


def test_fallback_floods_other_bytes_after_a_path_send_without_an_answer():
    p, pc = report(400), command(400)

    async def go():
        br = make()
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]], "flood", binding=True, extra=[["g", G]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 1))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [(d.payload, d.data_type, d.path) for d in br.radio.sent] == [(pc, 0xFF4C, [b"\x20"]), (pc, 0xFF6C, None)]
    vec = V["retry"][0]
    assert q.command_channel_payload(bytes.fromhex(vec["pc"]), True).hex() == vec["payload_ff6c"] != vec["payload_ff4c"]
    assert br.stats.c["lt_fallback_sent"] == 1


def test_fallback_off_with_fb_0_and_after_zero_hop_only_with_both():
    p, pc = report(401), command(401)

    async def go(route, hops, fb):
        br = make()
        await br.start()
        extra = [["g", G]] + ([["fb", "0"]] if not fb else [])
        await br.on_relay_event(mailbox_event(pc, [p[:4]], route, binding=True, extra=extra).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, hops))
        await br.drain()
        await br.stop()
        return [d.data_type for d in br.radio.sent]

    assert run(go("flood", 0, True)) == [0xFF4C]          # zero-hop, route flood: no fallback
    assert run(go("both", 0, True)) == [0xFF4C, 0xFF6C]   # zero-hop with both: fallback
    assert run(go("both", 0, False)) == [0xFF4C]          # fb 0
    assert run(go("flood", 1, False)) == [0xFF4C]


def test_answer_along_the_path_cancels_the_fallback_and_closes_the_group():
    """Item 17: a slot send along the path; the answer comes back DIRECT with an empty path (copy 11 of n + 1,
    a y of the group's next untimed entry): no fallback flood, the group is done."""
    via = report(410, 0)
    n1 = [report(411, j)[:4] for j in lt2.J_ON_AIR]

    async def go():
        br = make(timed_claim_lead_s=0.05)
        br.cfg.libretag.fallback_after_s = 0.5
        await br.start()
        await br.on_datagram(flood(via, P2[:1], hs=2))
        await br.drain()
        now = int(br.clock_ref["t"])
        at1, at2 = now + 900, now + 1800
        for at_, c in ((at1, 1), (at2, 2)):
            tags = [["v", "2"], ["y", k(2000 + c)[:4].hex()], ["route", "flood"], ["g", G],
                    ["expiration", str(now + 48 * 3600)], ["at", str(at_)], ["via", via[:4].hex()]]
            await br.on_relay_event(sign_event(owner_sk(via[:4]), now, 4244, tags,
                                               base64.b64encode(command(410, c)).decode()).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(command(411), n1, "flood", binding=True, created_at=now,
                                              extra=[["g", G]]).to_dict(), "wss://a")
        br.clock_ref["t"] = at1 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await asyncio.sleep(0.2)              # the slot send went out; the fallback waits
        br.clock_ref["t"] = at1 + 3
        await br.on_datagram(direct(report(411, 11)))  # the answer, along the path
        await br.drain()
        br.clock_ref["t"] = at2 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    sent = [(d.payload, d.data_type, d.path) for d in br.radio.sent]
    assert sent[0] == (command(410, 1), 0xFF4C, [bytes.fromhex("a5e1")])
    assert all(dt != 0xFF6C for _, dt, _ in sent)                  # no fallback flood
    assert command(410, 2) not in [s[0] for s in sent]              # the group's next slot is dropped
    assert br.ledger.group_done(G) and br.stats.c["lt_fallback_cancelled"] == 1
    assert br.stats.c["lt_path_cmd_confirmed"] == 1


def test_report_5_s_after_via_with_the_next_counters_y_closes_the_group():
    via = report(420, 9)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(direct(via))
        await br.drain()
        now = int(br.clock_ref["t"])
        tags = [["v", "2"], ["y", k(2100)[:4].hex()], ["route", "flood"], ["g", G],
                ["expiration", str(now + 48 * 3600)], ["at", str(now + 900)], ["via", via[:4].hex()]]
        await br.on_relay_event(sign_event(owner_sk(via[:4]), now, 4244, tags,
                                           base64.b64encode(command(420, 2)).decode()).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(command(421), [report(421, j)[:4] for j in lt2.J_ON_AIR], "flood",
                                              binding=True, created_at=now, extra=[["g", G]]).to_dict(), "wss://a")
        br.clock_ref["t"] = now + 5
        await br.on_datagram(direct(report(421, 9)))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.ledger.group_done(G) and br.stats.c["lt_group_answered"] == 1


def test_the_report_an_untimed_entry_waits_for_does_not_close_the_group():
    """R34: hearing report n, which the group's untimed entry for n waits for, answers nothing yet (no send, no
    timed entry). The group stays open, so the timed entries the app publishes on n's grid afterwards are
    taken; report n + 1 then closes it."""
    pn, pn1 = report(450, 9), report(451, 9)

    async def go():
        br = make()
        await br.start()
        now = int(br.clock_ref["t"])
        await br.on_relay_event(mailbox_event(command(450, 1), [report(450, j)[:4] for j in lt2.J_ON_AIR], "flood",
                                              binding=True, created_at=now - 10, extra=[["g", G]]).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(command(451, 1), [report(451, j)[:4] for j in lt2.J_ON_AIR], "flood",
                                              binding=True, created_at=now - 10, extra=[["g", G]]).to_dict(), "wss://a")
        await br.on_datagram(direct(pn))
        await br.drain()
        open_after_n = not br.ledger.group_done(G)
        tags = [["v", "2"], ["y", k(2200)[:4].hex()], ["route", "flood"], ["g", G],
                ["expiration", str(now + 48 * 3600)], ["at", str(now + 900)], ["via", pn[:4].hex()]]
        await br.on_relay_event(sign_event(owner_sk(pn[:4]), now, 4244, tags,
                                           base64.b64encode(command(450, 2)).decode()).to_dict(), "wss://a")
        br.clock_ref["t"] = now + 300
        await br.on_datagram(direct(pn1))
        await br.drain()
        await br.stop()
        return br, open_after_n

    br, open_after_n = run(go())
    assert open_after_n and br.stats.c["lt_group_done_before"] == 0
    assert br.stats.c["lt_mailbox_added_timed"] == 1
    assert br.ledger.group_done(G) and br.stats.c["lt_group_answered"] == 1


def test_a_timed_entry_on_an_older_grid_is_dropped_at_its_slot_after_the_next_report():
    """No send (near-only plain entries, heard over a repeater) and no timed entry when reports n and n + 1 were
    heard; then a timed entry of the group on n's
    grid arrives. At its slot the bridge sees that n + 1 (another untimed entry) came after its via: the group
    is done and nothing goes on air."""
    pn, pn1 = report(454, 9), report(455, 9)

    async def go():
        br = make(timed_claim_lead_s=0.05, claim=False)
        await br.start()
        now = int(br.clock_ref["t"])
        for n_ in (454, 455):
            await br.on_relay_event(mailbox_event(command(n_, 1), [report(n_, j)[:4] for j in lt2.J_ON_AIR], "near",
                                                  binding=True, created_at=now - 10, extra=[["g", G]]).to_dict(),
                                    "wss://a")
        br.clock_ref["t"] = now + 1
        await br.on_datagram(flood(pn, P2[:1]))   # over a repeater: the near-only plain entries are not ours
        br.clock_ref["t"] = now + 300
        await br.on_datagram(flood(pn1, P2[:1]))
        await br.drain()
        assert br.radio.sent == []
        tags = [["v", "2"], ["y", k(2500)[:4].hex()], ["route", "flood"], ["g", G],
                ["expiration", str(now + 48 * 3600)], ["at", str(now + 900)], ["via", pn[:4].hex()]]
        await br.on_relay_event(sign_event(owner_sk(pn[:4]), now + 300, 4244, tags,
                                           base64.b64encode(command(454, 2)).decode()).to_dict(), "wss://a")
        open_before = not br.ledger.group_done(G)
        br.clock_ref["t"] = now + 900
        br.lt2.timed_tick(now + 900)
        await br.drain()
        await br.stop()
        return br, open_before

    br, open_before = run(go())
    assert open_before
    assert br.radio.sent == []  # the slot of the older grid: nothing
    assert br.ledger.group_done(G) and br.stats.c["lt_cmd_group_done"] == 1


def test_a_sibling_copy_of_an_unheard_via_is_not_an_answer():
    """The timed entry names via x(n, 0), which this bridge did not hear; it hears copy 9 of the same report n
    (a y of the same untimed entry). That is report n itself, not the next report: the group stays open."""
    v0, c9 = report(452, 0), report(452, 9)

    async def go():
        br = make()
        await br.start()
        now = int(br.clock_ref["t"])
        await br.on_relay_event(mailbox_event(command(452, 1), [report(452, j)[:4] for j in lt2.J_ON_AIR], "flood",
                                              binding=True, created_at=now - 10, extra=[["g", G]]).to_dict(), "wss://a")
        tags = [["v", "2"], ["y", k(2300)[:4].hex()], ["route", "flood"], ["g", G],
                ["expiration", str(now + 48 * 3600)], ["at", str(now + 900)], ["via", v0[:4].hex()]]
        await br.on_relay_event(sign_event(owner_sk(v0[:4]), now - 10, 4244, tags,
                                           base64.b64encode(command(452, 2)).decode()).to_dict(), "wss://a")
        br.clock_ref["t"] = now + 200
        await br.on_datagram(direct(c9))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert not br.ledger.group_done(G) and br.stats.c["lt_group_answered"] == 0


def test_routed_ack_of_a_flood_copy_runs_the_claim_round_also_with_a_via():
    """PROTOCOL 4.3: the claim round goes before the first routed ACK of a flood, whatever tags the entry has."""
    n, p = 453, report(453, 0)

    async def go():
        br = make(key=ME)
        await br.start()
        await br.on_datagram(flood(p, P2[:1]))
        await br.on_relay_event(ack_event(ack_content(n, "both"), p[:4], "direct",
                                          extra=[["via", report(452, 0)[:4].hex()]]).to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert len(br.radio.sent) == 1
    claims = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND and any(t[0] == "hops" for t in e["tags"])]
    assert len(claims) == 1


def test_ack_goes_before_the_command_for_the_same_report():
    p = report(430, 9)
    content = ack_content(430, "near", more=True)

    async def go():
        br = make()
        br.cfg.libretag.ack_jitter_min_s = br.cfg.libretag.ack_jitter_max_s = 0.1
        br.cfg.libretag.ack_before_cmd_s = 0.05
        await br.start()
        await br.on_datagram(direct(p))
        await br.on_relay_event(ack_event(content, p[:4], "near").to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(command(430), [p[:4]], "near", binding=True).to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [content, command(430)]  # the late command waited for the ACK
    assert br.stats.c["lt_cmd_after_ack"] == 1


def test_ack_at_a_slot_goes_before_the_command_of_the_same_slot():
    """A timed ACK and a timed command for the same slot fall due at the same `at`: the ACK goes first and the
    command keeps ack_before_cmd_s after it, also when the ACK was already out when the command was ready."""
    p = report(431, 9)

    async def go():
        br = make(timed_claim_lead_s=0.05, claim=False)
        br.cfg.libretag.ack_before_cmd_s = 0.05
        await br.start()
        await br.on_datagram(direct(p))
        await br.drain()
        now = int(br.clock_ref["t"])
        at = now + 900
        ack = ack_content(431, "near", k_=1)
        await br.on_relay_event(ack_event(ack, k(2400)[:4], "near", at=at, vias=[p[:4]], created_at=now).to_dict(),
                                "wss://a")
        tags = [["v", "2"], ["y", k(2401)[:4].hex()], ["route", "near"], ["expiration", str(at + 120)],
                ["at", str(at)], ["via", p[:4].hex()]]
        await br.on_relay_event(sign_event(owner_sk(p[:4]), now, 4244, tags,
                                           base64.b64encode(command(431)).decode()).to_dict(), "wss://a")
        br.clock_ref["t"] = at
        br.lt2.timed_tick(at)
        await br.drain()
        await br.stop()
        return br, ack

    br, ack = run(go())
    assert [d.payload for d in br.radio.sent] == [ack, command(431)]
    assert br.stats.c["lt_cmd_after_ack"] == 1


def test_the_ack_budget_holds_for_timed_acks_with_a_miss_note():
    p = report(432, 9)

    async def go():
        br = make(key=ME, timed_claim_lead_s=0.05, acks_per_hour=0)
        await br.start()
        await br.on_datagram(direct(p))
        await br.drain()
        now = int(br.clock_ref["t"])
        at = now + 900
        await br.on_relay_event(ack_event(ack_content(432, "near", k_=1), k(2600)[:4], "near", at=at, vias=[p[:4]],
                                          created_at=now).to_dict(), "wss://a")
        br.clock_ref["t"] = at
        br.lt2.timed_tick(at)
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_ack_hourly_cap"] == 1
    assert [t for t in notes(br, "miss")[0]["tags"] if t[0] == "miss"] == [["miss", "blocked"]]


def test_late_entries_until_40_s():
    from nostrmesh.config import LibreTagCfg
    assert LibreTagCfg().late_max_s == 40 and LibreTagCfg().direct_max_hops == 2 and LibreTagCfg().receipts


# ---------------------------------------------------------------- timed entries (R20), blind entries


class FlakyRadio(MemoryDirectRadio):
    """Refuses the first `fails` sends, each taking 2 s of the bridge's clock."""

    def __init__(self, clock, fails):
        super().__init__()
        self.clock, self.fails = clock, fails

    def send_direct(self, *a, **kw):
        if self.fails:
            self.fails -= 1
            self.clock["t"] += 2
            raise RadioError("channel.sendData refused: timeout", code="timeout")
        return super().send_direct(*a, **kw)


def test_radio_error_at_at_then_success_4_s_later_sends_once():
    p, pc = report(440, 9), command(440)
    clock = {}

    async def go():
        radio = FlakyRadio(clock, 2)
        br = make(radio=radio, timed_claim_lead_s=0.05, claim=False)
        clock.update(br.clock_ref)
        br.clock = lambda: clock["t"]
        br.clock_ref = clock
        await br.start()
        await br.on_datagram(direct(p))
        await br.drain()
        at = int(clock["t"]) + 900
        tags = [["v", "2"], ["y", k(2200)[:4].hex()], ["route", "near"], ["expiration", str(at + 120)],
                ["at", str(at)], ["via", p[:4].hex()]]
        await br.on_relay_event(sign_event(owner_sk(p[:4]), int(clock["t"]), 4244, tags,
                                           base64.b64encode(pc).decode()).to_dict(), "wss://a")
        clock["t"] = at
        br.lt2.timed_tick(at)
        await br.drain()
        br.lt2.timed_tick(clock["t"])
        await br.drain()
        await br.stop()
        return br, at

    br, at = run(go())
    assert [d.payload for d in br.radio.sent] == [pc] and br.stats.c["lt_cmd_timed_sent"] == 1
    assert br.stats.c["lt_cmd_timed_retry"] == 2 and notes(br, "miss") == []


def test_timed_send_refused_until_at_plus_20_gives_a_miss_note():
    p, pc = report(441, 9), command(441)
    clock = {}

    async def go():
        br = make(radio=FlakyRadio(clock, 99), key=ME, timed_claim_lead_s=0.05, claim=False)
        clock.update(br.clock_ref)
        br.clock = lambda: clock["t"]
        await br.start()
        await br.on_datagram(direct(p))
        await br.drain()
        at = int(clock["t"]) + 900
        tags = [["v", "2"], ["y", k(2201)[:4].hex()], ["route", "near"], ["g", G], ["expiration", str(at + 120)],
                ["at", str(at)], ["via", p[:4].hex()]]
        ev = sign_event(owner_sk(p[:4]), int(clock["t"]), 4244, tags, base64.b64encode(pc).decode())
        await br.on_relay_event(ev.to_dict(), "wss://a")
        clock["t"] = at
        br.lt2.timed_tick(at)
        await br.drain()
        await br.stop()
        return br, ev, at

    br, ev, at = run(go())
    assert br.radio.sent == []
    m = notes(br, "miss")
    assert len(m) == 1 and m[0]["tags"] == [["e", ev.id], ["g", G], ["at", str(at)], ["miss", "radio"]]


@pytest.mark.parametrize("named", [False, True])
def test_no_path_miss_note_only_for_an_entry_naming_this_bridge(named):
    """A timed `near` entry for a via heard over a hop cannot go from here. Of an open entry many bridges hold
    a copy: no miss note. An entry that names this bridge in p gets one."""
    p, pc = report(442, 0)[:], command(442)

    async def go():
        br = make(key=ME, timed_claim_lead_s=0.05, claim=False)
        await br.start()
        await br.on_datagram(flood(p, P2[:1]))
        await br.drain()
        at = int(br.clock_ref["t"]) + 900
        tags = [["v", "2"], ["y", k(2202)[:4].hex()], ["route", "near"], ["g", G], ["expiration", str(at + 120)],
                ["at", str(at)], ["via", p[:4].hex()]] + ([["p", secp.pubkey(ME).hex()]] if named else [])
        ev = sign_event(owner_sk(p[:4]), int(br.clock_ref["t"]), 4244, tags, base64.b64encode(pc).decode())
        await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = at
        br.lt2.timed_tick(at)
        await br.drain()
        await br.stop()
        return br, ev

    br, ev = run(go())
    assert br.radio.sent == []
    m = notes(br, "miss")
    if named:
        assert len(m) == 1 and ["miss", "no_path"] in m[0]["tags"]
    else:
        assert m == [] and br.stats.c["lt_no_path_open"] == 1


def test_unknown_reply_scope_is_the_narrowest_region():
    """A scoped flood whose transport code matches none of the regions is answered in the narrowest one."""
    p = report(443, 0)
    dg = Datagram(*LT, p, 3, 8.0, path=[b"\x01\x02"] * 3, path_hash_size=2, route_type="tflood")
    dg.transport_code = 1  # no region gives this code

    async def go():
        br = make()
        br.cfg.radio.regions = ["sk-test", "sk"]
        await br.start()
        await br.on_relay_event(mailbox_event(command(443), [p[:4]], "flood", binding=True).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(dg)
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [(d.payload, d.scope) for d in br.radio.sent] == [(command(443), "sk-test")]


def test_blind_lost_entry_floods_in_the_country_scope_without_via():
    via = report(450, 0)  # never heard

    async def go():
        br = make(key=ME, timed_claim_lead_s=0.05)
        br.cfg.radio.regions = ["sk-test", "sk"]
        await br.start()
        now = int(br.clock_ref["t"])
        from nostrmesh import secp
        tags = [["v", "2"], ["y", k(2300)[:4].hex()], ["route", "flood"], ["blind", "1"],
                ["p", secp.pubkey(ME).hex()], ["expiration", str(now + 3600)], ["at", str(now + 60)],
                ["via", via[:4].hex()]]
        lost = q.seal_slot_command(q.slot_key(KEYS.cmd(epoch(450)), 450), 1, Command.set_mode(5, lt2.Mode.LOST, 600, 1440))
        await br.on_relay_event(sign_event(owner_sk(via[:4]), now, 4244, tags,
                                           base64.b64encode(lost).decode()).to_dict(), "wss://a")
        br.clock_ref["t"] = now + 60 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br, lost

    br, lost = run(go())
    assert [(d.payload, d.path, d.scope) for d in br.radio.sent] == [(lost, None, "sk")]


# ---------------------------------------------------------------- radio health (R37, acceptance item 16)


class Unplugged(MemoryDirectRadio):
    radio_connected = True

    def probe(self):
        return {"connected": self.radio_connected}


def test_radio_outage_down_announced_miss_note_hooks_and_back(tmp_path):
    hooks = tmp_path / "hooks.txt"
    p, pc = report(460, 9), command(460)

    async def go():
        radio = Unplugged()
        br = make(radio=radio, key=ME, timed_claim_lead_s=0.05, claim=False)
        w = br.cfg.watchdog
        w.rx_silence_s, w.step_wait_s, w.start_grace_s = 1800, 0, 0
        br.cfg.libretag.timed_retry_until_s = 0.1  # the test clock stands still: few retries
        w.alert_cmd = f'echo "alert $NOSTRMESH_RADIO_STATE $NOSTRMESH_RADIO_REASON" >> {hooks}'
        w.recover_cmd = f'echo "recover $NOSTRMESH_RADIO_STATE" >> {hooks}'
        await br.start()
        await br.on_datagram(direct(p))
        await br.drain()
        t0 = br.clock_ref["t"]
        at = int(t0) + 90
        tags = [["v", "2"], ["y", k(2400)[:4].hex()], ["route", "near"], ["g", G], ["expiration", str(at + 120)],
                ["at", str(at)], ["via", p[:4].hex()]]
        ev = sign_event(owner_sk(p[:4]), int(t0), 4244, tags, base64.b64encode(pc).decode())
        await br.on_relay_event(ev.to_dict(), "wss://a")
        radio.radio_connected = False                 # the USB cable is pulled
        downs = []
        for s in range(10, 130, 10):                  # the health loop, every 10 s
            br.clock_ref["t"] = t0 + s
            await br.health.tick(br.clock_ref["t"])
            downs.append(br.health.state)
        await br.drain()
        br.clock_ref["t"] = at
        br.lt2.timed_tick(at)                         # a slot during the outage
        await br.drain()
        for s in (300, 1200, 4800):                   # recovery rounds at 5, 15, 60 min back-off
            br.clock_ref["t"] = t0 + 10 + s + 1
            await br.health.tick(br.clock_ref["t"])
            await br.drain()
        radio.radio_connected = True                  # cable back: the next probe (every minute while down)
        br.clock_ref["t"] += 61
        await br.health.tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br, downs, ev

    br, downs, ev = run(go())
    assert downs[0] == "down" and br.health.state == "up"      # within 10 s of the cable, well under 2 minutes
    anns = [Event.from_dict(e) for e in br.pool.published if e["kind"] == market.ANNOUNCE_KIND]
    states = [market.parse_announcement(a, T0)["radio"][0] for a in anns]
    assert states == ["up", "down", "up"]
    m = notes(br, "miss")
    assert len(m) == 1 and ["miss", "radio"] in m[0]["tags"] and ["e", ev.id] in m[0]["tags"]
    lines = hooks.read_text().split("\n")
    assert lines[0] == "alert down api_says_down"
    assert lines[1:4] == ["recover recover"] * 3                # once per back-off round
    assert lines[4].startswith("alert power_cycle_needed") and lines[5].startswith("alert up")
    assert not br.fatal.is_set() and br.stats.c["radio_down"] == 1 and br.stats.c["radio_up"] == 1


def test_two_timed_out_sends_or_probes_make_the_radio_down():
    class Probe(MemoryDirectRadio):
        def probe(self):
            raise RadioError("radio.stats: timeout", code="timeout")

    async def go():
        br = make(radio=Probe())
        br.cfg.watchdog.probe_s, br.cfg.watchdog.start_grace_s = 300, 0
        await br.start()
        states = []
        for i in (1, 2):
            br.clock_ref["t"] += 301
            await br.health.tick(br.clock_ref["t"])
            states.append(br.health.state)
        await br.drain()
        await br.stop()
        return br, states

    br, states = run(go())
    assert states == ["up", "down"] and br.health.reason == "probe_timeout"


def test_degraded_from_the_probe_and_reboot_after_ten_minutes():
    class Node(MemoryDirectRadio):
        late = 0
        reboots = 0

        def probe(self):
            Node.late += 1
            return {"connected": True, "link": {"lateReplies": Node.late}}

        def reboot(self):
            Node.reboots += 1
            return "ok"

    async def go(reboot_s):
        Node.late = Node.reboots = 0
        br = make(radio=Node())
        br.cfg.watchdog.probe_s, br.cfg.watchdog.start_grace_s = 60, 0
        br.cfg.watchdog.degraded_reboot_s = reboot_s
        await br.start()
        for _ in range(13):
            br.clock_ref["t"] += 61
            await br.health.tick(br.clock_ref["t"])
            await br.drain()
        await br.stop()
        return br

    br = run(go(600))
    assert br.health.degraded and br.health.state == "up" and Node.reboots == 1
    # the default (0) only reports the degraded radio: a reboot did not cure the stalls in the field
    br = run(go(0))
    assert br.health.degraded and br.health.state == "up" and Node.reboots == 0


def test_example_config_loads_and_the_old_region_key_is_refused(tmp_path):
    from nostrmesh.config import Config
    c = Config.load(Path(__file__).resolve().parents[1] / "bridge.example.toml")
    assert c.libretag.late_max_s == 40 and c.libretag.direct_max_hops == 2 and c.watchdog.probe_s == 300
    assert "wss://nos.lol" not in c.publish
    f = tmp_path / "old.toml"
    f.write_text('[radio]\nregion = "sk"\n[libretag]\nack_window_s = 180\nmax_hops = 0\n')
    with pytest.raises(ValueError, match="regions"):
        Config.load(f)
    f.write_text('[radio]\nregions = ["sk"]\n[libretag]\nack_window_s = 180\n')
    assert Config.load(f).radio.regions == ["sk"]  # retired [libretag] keys are only logged

"""Bridge keys, announcements, receipts, targeted entries and the quick fixes Q1-Q6 (PROTOCOL.md 4.1,
docs/design/bridge-market.md A.6 and B)."""
import base64
import json
from pathlib import Path

import pytest

from libretag import lt2
from nostrmesh import lt2svc, market, secp
from nostrmesh.ledger import Ledger
from nostrmesh.nostr import Event, sign_event
from nostrmesh.radio import Datagram, MemoryDirectRadio, MemoryRadio
from test_lt2bridge import LT, T0, command, heard, k, make, mailbox_event, owner_sk, report, run, xc

VEC = json.loads((Path(__file__).parent / "vectors" / "mailbox-v2.json").read_text())
FREE: dict = {}
ME, OTHER, THIRD = k(801), k(802), k(803)


def pk(sk):
    return secp.pubkey(sk).hex()


def v2(pc, ys, route="flood", p=(), r=None, sk=None, created_at=T0 - 100, at=None, via=None):
    tags = [["y", y.hex()] for y in ys] + [["v", "2"], ["route", route], ["expiration", str(T0 + 48 * 3600)]]
    tags += [["p", x] for x in p] + ([["r", r]] if r else [])
    if at is not None:
        tags += [["at", str(at)], ["via", via.hex()]]
    return sign_event(sk or owner_sk(via if via is not None else ys[0]), created_at, lt2.NOSTR_COMMAND_KIND, tags,
                      base64.b64encode(pc).decode())


def claim(sk, y, hops, proof=None, created_at=T0):
    tags = [["y", y.hex()], ["hops", str(hops)]] + ([["proof", proof]] if proof else [])
    return sign_event(sk, created_at, lt2.NOSTR_CLAIM_KIND, tags, "")


def announcement(sk, now=T0, network="sk", exp=None):
    tags = market.announce_tags(network, now)
    if exp is not None:
        tags = [t for t in tags if t[0] != "expiration"] + [["expiration", str(exp)]]
    return sign_event(sk, now, market.ANNOUNCE_KIND, tags, "{}")


def of_kind(br, kind):
    return [e for e in br.pool.published if e["kind"] == kind]


# ---------------------------------------------------------------- vectors


def test_vectors_file_is_current():
    assert market.vectors() == VEC


def test_vectors_targeting_proof_hops_lock():
    for c in VEC["targeting"]:
        t = market.targeting(c["tags"])
        assert (t is not None) == c["valid"], c["note"]
        if t:
            assert t.to_dict() == c["parsed"], c["note"]
    for c in VEC["proof"]:
        assert market.claim_proof(bytes.fromhex(c["p"]), c["pubkey"]) == c["proof"]
    for c in VEC["hops_class"]:
        assert market.hops_class(c["hops"]) == c["class"]
    for c in VEC["lock"]:
        for kk in c["keys"]:
            assert lt2.commit_matches(bytes.fromhex(kk["pubkey"]), bytes.fromhex(c["p"])) == kk["fits"], kk["note"]


def test_vector_events_through_the_bridge():
    """Each vector event at a bridge whose key is the vectors' bridge key: format and targeting verdicts, and
    which key a heard report locks."""
    bridge_sk = secp.seckey_from_bytes(__import__("hashlib").sha256(b"mailbox v2 vector 100").digest())
    assert pk(bridge_sk) == VEC["bridge_pubkey"]
    lock = VEC["lock"][0]
    for c in VEC["events"]:
        async def go():
            br = make(key=bridge_sk, now=c["event"]["created_at"] + 10, auto_anchor=False)
            await br.on_relay_event(c["event"], "wss://a")
            br.clock_ref["t"] += 1
            await br.on_datagram(heard(bytes.fromhex(lock["p"]), 0))
            await br.drain()
            return br
        br = run(go())
        ev = c["event"]
        if ev["content"] == "":
            stored = ev["pubkey"] in br.ledger.keys_of_y(lock["x"])
        else:
            stored = br.ledger.mailbox_pending(ev["id"]) or br.ledger.mailbox_sent(ev["id"])
        assert stored == (c["valid"] and c["for_bridge"]), c["note"]
        if stored and "at" not in [t[0] for t in ev["tags"]]:
            assert (ev["pubkey"] in br.ledger.locked(lock["x"])) == c["signed_by_pk_n"], c["note"]


# ---------------------------------------------------------------- Q1, Q5


def test_q1_first_seen_entry_wins_over_a_later_copy_of_the_y_tags():
    p, pc = report(500), command(500)
    junk = bytes(29)

    async def go():
        br = make()
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]], created_at=T0 - 100).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        # an attacker copies the owner's y with a later created_at and junk content
        await br.on_relay_event(mailbox_event(junk, [p[:4]], created_at=T0 + 50, sk=k(66)).to_dict(), "wss://b")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 1))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc]


def test_q5_only_the_first_hearing_triggers():
    """A stranger's entry published after the report reached the relays cannot ride on a later flood copy.
    (The owner's own key may: that is a late entry, PROTOCOL.md 4.2, sent once at once.)"""
    p, pc = report(510), command(510)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p, 3))  # first copy: nothing pending
        await br.drain()
        br.clock_ref["t"] += 2
        await br.on_relay_event(mailbox_event(pc, [p[:4]], sk=k(66)).to_dict(), "wss://a")
        br.clock_ref["t"] += 2
        await br.on_datagram(heard(p, 1))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_mailbox_unsigned"] == 1


# ---------------------------------------------------------------- targeting (format 2)


def test_targeting_store_rules():
    pc = command(520)
    y = report(520)[:4]

    async def go(ev, **lt):
        br = make(key=ME, **FREE, **lt)
        await br.on_relay_event(ev.to_dict(), "wss://a")
        return br, br.ledger.mailbox_pending(ev.id)

    assert run(go(v2(pc, [y], p=[pk(ME)])))[1]
    br, ok = run(go(v2(pc, [y], p=[pk(OTHER)])))
    assert not ok and br.stats.c["lt_mailbox_not_ours"] == 1
    assert run(go(v2(pc, [y], p=[pk(OTHER), pk(ME)])))[1]
    assert run(go(v2(pc, [y])))[1]                                     # open
    br, ok = run(go(v2(pc, [y]), accept_open=False))
    assert not ok and br.stats.c["lt_mailbox_open_off"] == 1
    assert run(go(v2(pc, [y], r="sk"), networks=["sk"]))[1]
    br, ok = run(go(v2(pc, [y], r="cz"), networks=["sk"]))
    assert not ok and br.stats.c["lt_mailbox_other_network"] == 1
    assert run(go(v2(pc, [y], r="cz")))[1]                             # no networks named: all
    no_v = sign_event(owner_sk(y), T0 - 100, lt2.NOSTR_COMMAND_KIND, [["y", y.hex()], ["route", "flood"]],
                      base64.b64encode(pc).decode())
    br, ok = run(go(no_v))
    assert not ok and br.stats.c["lt_mailbox_bad"] == 1               # format 1 is gone
    br, ok = run(go(v2(pc, [y], route="far")))
    assert not ok and br.stats.c["lt_mailbox_bad"] == 1


@pytest.mark.parametrize("route,hops,radio_cls,expect", [
    ("near", 0, MemoryDirectRadio, "zero-hop"),
    ("near", 1, MemoryDirectRadio, None),
    ("near", 0, MemoryRadio, None),
    ("direct", 2, MemoryDirectRadio, "direct"),
    ("direct", 4, MemoryDirectRadio, None),
    ("direct", 2, MemoryRadio, None),
    ("flood", 0, MemoryDirectRadio, "zero-hop"),
    ("flood", 5, MemoryDirectRadio, "flood"),
    ("flood", 5, MemoryRadio, "flood"),
])
def test_format2_route_is_binding(route, hops, radio_cls, expect):
    p, pc = report(530), command(530)

    async def go():
        br = make(radio=radio_cls(), key=ME, **FREE)
        await br.start()
        await br.on_relay_event(v2(pc, [p[:4]], route=route, p=[pk(ME)]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, hops))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    if expect is None:
        assert br.radio.sent == [] and br.stats.c["lt_cmd_route_too_far"] == 1
        assert br.ledger.answer(f"{p[:4].hex()}:{pk(owner_sk(p[:4]))}", T0)  # y left for a nearer bridge
    else:
        assert [d.payload for d in br.radio.sent] == [pc] and br.stats.c[f"lt_cmd_sent_{expect}"] == 1
        if expect == "direct":
            assert br.radio.sent[0].path == [bytes([0x21]), bytes([0x20])]  # reversed report path


def test_timed_near_entry_heard_far_is_left_alone():
    p, pc = report(540), command(540)

    async def go():
        br = make(key=ME, timed_claim_lead_s=0.05, **FREE)
        await br.start()
        await br.on_datagram(heard(p, 2))
        await br.drain()
        at = int(br.clock_ref["t"]) + 600
        await br.on_relay_event(v2(pc, [xc(40)], route="near", p=[pk(ME)], at=at, via=p[:4],
                                   created_at=int(br.clock_ref["t"])).to_dict(), "wss://a")
        br.clock_ref["t"] = at - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_cmd_route_too_far"] == 1


# ---------------------------------------------------------------- claims (Q2, Q3)


def _claim_scenario(rival, timed=False, peers=(), announce=None, p_tags=None):
    p, pc = report(550), command(550)

    async def go():
        br = make(key=ME, peers=list(peers), timed_claim_lead_s=0.05, **FREE)
        await br.start()
        if announce is not None:
            await br.on_relay_event(announcement(announce).to_dict(), "wss://a")
        if timed:
            await br.on_datagram(heard(p, 2))
            await br.drain()
            at = int(br.clock_ref["t"]) + 600
            await br.on_relay_event(v2(pc, [xc(50)], p=p_tags or [], at=at, via=p[:4],
                                       created_at=int(br.clock_ref["t"])).to_dict(), "wss://a")
            br.clock_ref["t"] = at - 0.05
            await br.on_relay_event(rival(p, xc(50)).to_dict(), "wss://b")
            br.lt2.timed_tick(br.clock_ref["t"])
        else:
            await br.on_relay_event(v2(pc, [p[:4]], p=p_tags or []).to_dict(), "wss://a")
            await br.on_relay_event(rival(p, p[:4]).to_dict(), "wss://b")
            br.clock_ref["t"] += 1
            await br.on_datagram(heard(p, 2))
        await br.drain()
        await br.stop()
        return br

    return run(go())


def test_untimed_claim_counts_from_announced_bridge_with_proof():
    good = lambda p, y: claim(OTHER, y, 0, market.claim_proof(p, pk(OTHER)))  # noqa: E731
    assert _claim_scenario(good, announce=OTHER).radio.sent == []                # it wins: fewer hops
    assert len(_claim_scenario(good).radio.sent) == 1                            # not announced: ignored
    bad = lambda p, y: claim(OTHER, y, 0, market.claim_proof(bytes(45), pk(OTHER)))  # noqa: E731
    assert len(_claim_scenario(bad, announce=OTHER).radio.sent) == 1             # wrong proof
    none = lambda p, y: claim(OTHER, y, 0)  # noqa: E731
    assert len(_claim_scenario(none, announce=OTHER).radio.sent) == 1            # no proof
    assert _claim_scenario(none, peers=[pk(OTHER)]).radio.sent == []             # a peer needs none
    stolen = lambda p, y: claim(OTHER, y, 0, market.claim_proof(p, pk(THIRD)))  # noqa: E731
    assert len(_claim_scenario(stolen, announce=OTHER).radio.sent) == 1          # proof made for another key


def test_untimed_claim_named_in_p_ranks_by_order():
    # the entry names ME first: a rival named second loses even with fewer hops
    good = lambda p, y: claim(OTHER, y, 0, market.claim_proof(p, pk(OTHER)))  # noqa: E731
    br = _claim_scenario(good, p_tags=[pk(ME), pk(OTHER)])
    assert len(br.radio.sent) == 1
    br = _claim_scenario(good, p_tags=[pk(OTHER), pk(ME)])
    assert br.radio.sent == []


def test_timed_claim_counts_only_from_peers():
    rival = lambda p, y: claim(OTHER, y, 0, created_at=T0 + 600)  # noqa: E731
    assert len(_claim_scenario(rival, timed=True, announce=OTHER).radio.sent) == 1
    assert _claim_scenario(rival, timed=True, peers=[pk(OTHER)]).radio.sent == []


def test_own_claims_signed_with_bridge_key_and_proof():
    br = _claim_scenario(lambda p, y: claim(THIRD, y, 9))
    c = of_kind(br, lt2.NOSTR_CLAIM_KIND)
    p = report(550)
    assert len(c) == 1 and c[0]["pubkey"] == pk(ME)
    assert ["proof", market.claim_proof(p, pk(ME))] in c[0]["tags"]
    assert Event.from_dict(c[0]).verify()


# ---------------------------------------------------------------- airtime order (Q6, B.4)


def test_open_flood_cap_and_targeted_entries_not_counted():
    async def go():
        br = make(key=ME, open_floods_per_hour=1, claim=False, **FREE)
        await br.start()
        for i, targeted in ((560, False), (561, False), (562, True)):
            p, pc = report(i), command(i)
            ev = v2(pc, [p[:4]], p=[pk(ME)] if targeted else [])
            await br.on_relay_event(ev.to_dict(), "wss://a")
            br.clock_ref["t"] += 1
            await br.on_datagram(heard(p, 4))
            await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [command(560), command(562)]
    assert br.stats.c["lt_cmd_open_flood_cap"] == 1


def test_open_flood_cap_checked_before_the_claim():
    """A capped bridge publishes no claim: it would win the round and keep a bridge that may send silent."""
    async def go():
        br = make(key=ME, open_floods_per_hour=1, **FREE)
        await br.start()
        for i in (563, 564):
            p, pc = report(i), command(i)
            await br.on_relay_event(v2(pc, [p[:4]]).to_dict(), "wss://a")
            br.clock_ref["t"] += 1
            await br.on_datagram(heard(p, 4))
            await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [command(563)]
    claims = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND and any(t[0] == "hops" for t in e["tags"])]
    assert len(claims) == 1 and br.stats.c["lt_cmd_open_flood_cap"] == 1


def test_timed_entries_keep_out_of_the_untimed_share():
    p, pc = report(570), command(570)

    async def go():
        br = make(key=ME, timed_claim_lead_s=0.05, timed_airtime_share=0.0, **FREE)
        await br.start()
        await br.on_datagram(heard(p, 2))
        await br.drain()
        at = int(br.clock_ref["t"]) + 600
        await br.on_relay_event(v2(pc, [xc(70)], p=[pk(ME)], at=at, via=p[:4],
                                   created_at=int(br.clock_ref["t"])).to_dict(), "wss://a")
        br.clock_ref["t"] = at - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_cmd_timed_airtime_share"] == 1


# ---------------------------------------------------------------- receipts, announcements, subscriptions


def test_receipt_on_first_live_hearing_only_when_enabled():
    p = report(580)

    async def go(receipts=True, key=ME, stale=False, hops=0, direct=False):
        br = make(key=key, receipts=receipts)
        await br.start()
        dg = heard(p, hops, rx_time=T0 - 1)
        if direct:
            dg.route_type, dg.hops = "direct", None
        dg.stale = stale
        await br.on_datagram(dg)
        await br.on_datagram(heard(p, 1))
        await br.drain()
        await br.stop()
        return of_kind(br, market.RECEIPT_KIND)

    r = run(go())  # on by default, a flood with 0 hops
    assert len(r) == 1 and r[0]["pubkey"] == pk(ME) and r[0]["created_at"] == T0 - 1
    assert r[0]["tags"] == [["y", p[:4].hex()], ["hops", "0"], ["expiration", str(T0 - 1 + 48 * 3600)]]
    r = run(go(direct=True))  # a DIRECT arrival: the hop count is unknown
    assert r[0]["tags"] == [["y", p[:4].hex()], ["route", "direct"], ["expiration", str(T0 - 1 + 48 * 3600)]]
    assert run(go(hops=2)) == []  # no receipt for a flood heard over repeaters
    assert run(go(receipts=False)) == []
    assert run(go(key=None)) == []
    assert run(go(stale=True)) == []


def test_announcement_published_at_start_and_learned_from_relays():
    async def go():
        br = make(key=ME, networks=["sk"])
        await br.start()
        await br.drain()
        await br.on_relay_event(announcement(OTHER).to_dict(), "wss://a")
        await br.on_relay_event(announcement(THIRD, exp=T0 - 1).to_dict(), "wss://a")  # expired
        forged = announcement(k(804)).to_dict()
        forged["pubkey"] = pk(k(805))
        await br.on_relay_event(forged, "wss://a")
        await br.stop()
        return br

    br = run(go())
    a = of_kind(br, market.ANNOUNCE_KIND)
    assert len(a) == 1 and a[0]["pubkey"] == pk(ME)
    parsed = market.parse_announcement(Event.from_dict(a[0]), T0)
    assert parsed["network"] == "sk" and parsed["signed"] == [0, 40]  # make() sets anchor_lead_s 0
    assert parsed["radio"] == ("up", T0)
    assert not any(t[0] == "pow" for t in a[0]["tags"])
    assert set(br.lt2.announced) == {pk(OTHER)}
    assert br.lt2.known(pk(OTHER)) and not br.lt2.known(pk(THIRD))


def test_no_key_no_announcement_and_claims_under_fresh_keys():
    async def go():
        br = make()
        await br.start()
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert of_kind(br, market.ANNOUNCE_KIND) == [] and br.lt2.pubkey is None


def test_subscriptions_without_open_entries_still_get_anchors():
    """accept_open off still reads every 4244: anchors carry no tag a filter could select, and without
    them no key locks and no ACK entry goes out (PROTOCOL.md 4.1, 4.3). Open commands drop on arrival."""
    br = make(key=ME, networks=["sk"], accept_open=False)
    every = [{"kinds": [lt2.NOSTR_COMMAND_KIND], "since": int(T0 - 48 * 3600)}]
    assert br.lt2.subscriptions(T0)["nm-lt-cmd"] == every
    assert make().lt2.subscriptions(T0)["nm-lt-cmd"] == every


def test_publish_delay_floor_above_claim_wait():
    from nostrmesh.config import LibreTagCfg
    c = LibreTagCfg()
    assert c.publish_delay_min_s > c.claim_wait_s and c.mailbox_cap >= 200_000
    br = make(publish_delay_min_s=2.0, publish_delay_max_s=5.0)
    assert all(2.0 <= br.lt2._publish_delay() <= 5.0 for _ in range(200))


def test_bridge_key_from_file(tmp_path):
    f = tmp_path / "bridge.key"
    f.write_text(ME.hex() + "\n")
    assert lt2svc.read_bridge_key(str(f)) == ME
    assert lt2svc.read_bridge_key("") is None


def test_local_api_region_checked_against_the_node_catalogue(tmp_path):
    from nostrmesh.localapi import Identity, LocalApiRadio

    cat = {"regions": [{"name": "sk"}, {"name": "sk-ke"}, {"name": "cz"}]}

    def radio(regions, answer):
        r = LocalApiRadio("ws://127.0.0.1:8787/api/v1", {}, identity=Identity(tmp_path / "id.json"), regions=regions)

        def request(m, p=None, timeout=None):
            if answer is None:
                from meshcore_localapi import RequestTimeout
                raise RequestTimeout(m, 15)
            return answer
        r.client.request = request
        r._check_region()
        return r

    r = radio(["sk-ke", "sk"], cat)
    assert r.regions == ["sk-ke", "sk"] and r.region_checked
    assert radio(["slovakia", "sk"], cat).regions == ["sk"]  # the node would refuse every send with it: dropped
    r = radio(["sk"], None)                                  # no answer: kept, checked again at the next hello
    assert r.regions == ["sk"] and not r.region_checked


def test_strfry_mailbox_policy():
    import importlib.util
    spec = importlib.util.spec_from_file_location("pol", Path(__file__).parents[1] / "deploy" / "strfry-mailbox-policy.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    assert mod.MIN_BITS == 0  # the signed mailbox needs no work; the rate limit stays
    assert (mod.BURST, mod.PER_MINUTE) == (120, 60)
    free = mod.Policy(per_minute=60, burst=2)
    cmd = {"id": "ff", "kind": 4244, "tags": [], "content": "x" * 40}
    assert [free.decide({"type": "new", "event": cmd, "sourceInfo": "9"}, t)["action"]
            for t in (0, 0.1, 0.2)] == ["accept", "accept", "reject"]
    assert free.decide({"type": "new", "event": cmd, "sourceInfo": "9"}, 1.3)["action"] == "accept"  # 1 a second
    # anchors and ACK entries (13 B, 10-minute expiration) count apart from commands
    ack = {"id": "fe", "kind": 4244, "content": "AAAAAAAAAAAAAAAAAA==",
           "tags": [["a", "1"], ["expiration", "600"]]}
    anchor = {"id": "fd", "kind": 4244, "content": "", "tags": []}
    assert [free.decide({"type": "new", "event": e, "sourceInfo": "9"}, 1.3)["action"] for e in (ack, anchor, ack)] \
        == ["accept", "accept", "reject"]
    burst = mod.Policy()
    assert sum(burst.decide({"type": "new", "event": cmd, "sourceInfo": "7"}, 0)["action"] == "accept"
               for _ in range(200)) == 120
    pol = mod.Policy(min_bits=16, per_minute=60, burst=2)  # an operator may still ask for work
    good = {"id": "0000" + "f" * 60, "kind": 4244, "tags": [["nonce", "1", "16"]]}

    def req(ev, src="1.2.3.4"):
        return {"type": "new", "event": ev, "sourceInfo": src}

    assert pol.decide(req({"id": "ab", "kind": 1, "tags": []}), 0)["action"] == "accept"
    assert pol.decide(req({**good, "tags": [["nonce", "1", "12"]]}), 0)["action"] == "reject"   # target too low
    assert pol.decide(req({**good, "id": "0001" + "f" * 60}), 0)["action"] == "reject"         # 15 bits
    assert pol.decide(req({**good, "tags": []}), 0)["action"] == "reject"
    assert [pol.decide(req(good), t)["action"] for t in (0, 0, 0)] == ["accept", "accept", "reject"]
    assert pol.decide(req(good, "5.6.7.8"), 0)["action"] == "accept"
    assert pol.decide(req(good), 61)["action"] == "accept"


def test_old_ledger_gets_format_columns(tmp_path):
    import sqlite3
    db = tmp_path / "old.sqlite"
    con = sqlite3.connect(db)
    con.execute("CREATE TABLE mailbox (event_id TEXT NOT NULL, y TEXT NOT NULL, payload BLOB NOT NULL, route TEXT "
                "NOT NULL, created_at INTEGER NOT NULL, expires REAL NOT NULL, known_at REAL NOT NULL, sent_at REAL, "
                "PRIMARY KEY (event_id, y))")
    con.execute("INSERT INTO mailbox VALUES ('e1', '11223344', x'00', 'near', 1, 9e9, 1, NULL)")
    con.commit()
    con.close()
    led = Ledger(db)
    [e] = led.mailbox_match("11223344", 5, 2)
    assert e.fmt == 1 and e.open and e.p == []

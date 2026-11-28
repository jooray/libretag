"""Bridge abuse limits on the LT2 mailbox (PROTOCOL.md 4 "Limits", "Command groups")."""
import asyncio
import base64
import json
from pathlib import Path

import pytest

from libretag import lt2
from nostrmesh import frames, secp
from nostrmesh.bridge import Bridge
from nostrmesh.config import Config
from nostrmesh.ledger import Ledger
from nostrmesh.nostr import Event, sign_event
from nostrmesh.radio import Datagram, MemoryDirectRadio

from test_lt2bridge import LT, T0, Pool, command, heard, k, make, owner_sk, report, run, xc


def pow_event(pc, tags, bits=0, sk=None, created_at=T0, target=None):
    """A kind 4244 signed by the owner's key of the report it names (via, else the first y). There is no
    proof of work any more (signed mailbox, PROTOCOL.md 4.2); `bits` and `target` are ignored."""
    via = next((t[1] for t in tags if t[0] == "via"), None)
    y = via or next(t[1] for t in tags if t[0] == "y")
    sk = sk or owner_sk(bytes.fromhex(y))
    return sign_event(sk, created_at, lt2.NOSTR_COMMAND_KIND, tags, base64.b64encode(pc).decode())


def timed_tags(xc_, at, via, g=None, created_at=T0):
    t = [["v", "2"], ["y", xc_.hex()], ["route", "flood"], ["expiration", str(created_at + 48 * 3600)],
         ["at", str(at)], ["via", via.hex()]]
    return t + ([["g", g]] if g else [])


def plain_tags(ys, g=None, created_at=T0):
    t = [["v", "2"]] + [["y", y.hex()] for y in ys] + [["route", "flood"], ["expiration", str(created_at + 48 * 3600)]]
    return t + ([["g", g]] if g else [])


def lowpow(**lt):
    return make(**lt)


# ---------------------------------------------------------------- future created_at


def test_future_created_at_refused():
    p = report(510)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        ok = pow_event(command(511), timed_tags(xc(51), now + 200, p[:4], created_at=now), created_at=now)
        future = pow_event(command(514), timed_tags(xc(54), now + 200, p[:4], created_at=now + 61),
                           created_at=now + 61)
        for ev in (ok, future):
            await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.stop()
        return br

    br = run(go())
    assert br.stats.c["lt_mailbox_added_timed"] == 1 and br.stats.c["lt_spam_future"] == 1


# ---------------------------------------------------------------- A1-A6


def test_a1_at_must_follow_report_via_within_25h():
    p, q = report(520), report(521)

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        from test_lt2bridge import timed_event
        await br.on_relay_event(timed_event(command(520), xc(60), now + 25 * 3600 + 10, p[:4], created_at=now).to_dict(),
                                "wss://a")  # past the heartbeat: refused
        await br.on_relay_event(timed_event(command(522), xc(61), now + 24 * 3600, p[:4], created_at=now).to_dict(),
                                "wss://a")  # fine
        # via not heard yet: kept, but checked again when due
        await br.on_relay_event(timed_event(command(523), xc(62), now + 26 * 3600, q[:4], created_at=now).to_dict(),
                                "wss://a")
        br.clock_ref["t"] = now + 3600
        await br.on_datagram(heard(q, 0))  # q heard an hour later: at - heard_at = 25 h + ... > 25 h
        await br.drain()
        br.clock_ref["t"] = now + 26 * 3600 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.stats.c["lt_spam_timed_window"] == 1  # the first refused on arrival
    assert br.stats.c["lt_mailbox_added_timed"] == 2
    # the q entry: at = heard + 25 h exactly, so it is still allowed (<=)
    assert [d.payload for d in br.radio.sent] == [command(523)]


def test_a1_entry_before_its_report_checked_when_due():
    p = report(525)

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        now = int(br.clock_ref["t"])
        from test_lt2bridge import timed_event
        await br.on_relay_event(timed_event(command(525), xc(63), now + 30 * 3600, p[:4], created_at=now).to_dict(),
                                "wss://a")
        br.clock_ref["t"] = now + 60
        await br.on_datagram(heard(p, 0))
        await br.drain()
        br.clock_ref["t"] = now + 30 * 3600 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_spam_timed_window"] == 1


def test_a2_at_most_12_timed_entries_per_via_earlier_seen_stay():
    p = report(530)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        evs = []
        for i in range(14):
            ev = pow_event(command(600 + i), timed_tags(xc(100 + i), now + 900 * (i + 1), p[:4], created_at=now),
                           created_at=now)
            evs.append(ev)
            await br.on_relay_event(ev.to_dict(), "wss://a")
            br.clock_ref["t"] += 0.01
        await br.stop()
        return br, evs

    br, evs = run(go())
    kept = {r[0] for r in br.ledger._all("SELECT event_id FROM mailbox")}
    assert kept == {ev.id for ev in evs[:12]}  # the two seen last go
    assert br.stats.c["lt_spam_evicted"] == 2


def test_a3_one_timed_transmission_per_via_per_10_min_and_a4_hourly_cap():
    vias = [report(540 + i) for i in range(4)]

    async def go():
        br = make(timed_claim_lead_s=0.05, timed_per_hour=3)
        await br.start()
        for v in vias:
            await br.on_datagram(heard(v, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        from test_lt2bridge import timed_event
        evs = [timed_event(command(540), xc(70), now + 600, vias[0][:4], created_at=now),
               timed_event(command(541), xc(71), now + 900, vias[0][:4], created_at=now),   # 5 min later
               timed_event(command(542), xc(72), now + 1300, vias[0][:4], created_at=now),  # 11.7 min
               timed_event(command(543), xc(73), now + 1400, vias[1][:4], created_at=now),
               timed_event(command(544), xc(74), now + 1500, vias[2][:4], created_at=now)]  # 4th in the hour
        for ev in evs:
            await br.on_relay_event(ev.to_dict(), "wss://a")
        for ev in evs:
            at = int(next(t[1] for t in ev.tags if t[0] == "at"))
            br.clock_ref["t"] = at - 0.05
            br.lt2.timed_tick(br.clock_ref["t"])
            await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [command(540), command(542), command(543)]
    assert br.stats.c["lt_cmd_timed_via_gap"] == 1 and br.stats.c["lt_cmd_timed_hourly_cap"] == 1


def test_a3_a4_limits_survive_restart(tmp_path):
    p = report(545)
    db = tmp_path / "l.sqlite"

    async def go():
        from test_lt2bridge import timed_event
        br = make(ledger=Ledger(db), timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        for i, d in enumerate((300, 500)):
            await br.on_relay_event(timed_event(command(545 + i), xc(75 + i), now + d, p[:4], created_at=now).to_dict(), "wss://a")
        br.clock_ref["t"] = now + 300 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        br2 = make(ledger=Ledger(db), now=now + 500 - 0.05, timed_claim_lead_s=0.05)
        await br2.start()
        br2.lt2.timed_tick(br2.clock_ref["t"])
        await br2.drain()
        await br2.stop()
        return br, br2

    br, br2 = run(go())
    assert len(br.radio.sent) == 1 and br2.radio.sent == []
    assert br2.stats.c["lt_cmd_timed_via_gap"] == 1


def test_a4_libretag_airtime_separate_and_persisted(tmp_path):
    db = tmp_path / "l.sqlite"

    async def go():
        br = make(ledger=Ledger(db), airtime_pct=0.006)  # 0.216 s an hour: one 29 B command (0.18 s at SF7/CR5)
        await br.start()
        ok1 = await br.radio_send(frames.CHANNEL_LIBRETAG, frames.DATA_TYPE_LIBRETAG, command(550))
        ok2 = await br.radio_send(frames.CHANNEL_LIBRETAG, frames.DATA_TYPE_LIBRETAG, command(551))
        ok3 = await br.radio_send(frames.CHANNEL_ROAD, frames.DATA_TYPE_ROAD, b"r" * 60)  # other channels unaffected
        await br.stop()
        br2 = make(ledger=Ledger(db), airtime_pct=0.006)
        await br2.start()
        ok4 = await br2.radio_send(frames.CHANNEL_LIBRETAG, frames.DATA_TYPE_LIBRETAG, command(552))
        await br2.stop()
        return (ok1, ok2, ok3, ok4), br, br2

    oks, br, br2 = run(go())
    assert oks == (True, False, True, False)  # the restart did not reset the budget
    assert br2.lt_airtime.used(br2.clock()) > 0 and br2.airtime.used(br2.clock()) > 0


def test_a4_libretag_receive_limit_does_not_starve_other_channels():
    async def go():
        br = make(rx_per_min=5)
        br.cfg.uplink.rate_global_per_min = 3
        br.rx_global = type(br.rx_global)(3, 60)
        await br.start()
        for i in range(20):
            await br.on_datagram(heard(report(700 + i), 2))
        await br.on_datagram(heard(report(700), 2))  # a duplicate copy is never limited
        await br.drain()
        res = [await br.on_datagram(Datagram("#roadstr", 0x0150, b"\x01junk")) for _ in range(4)]
        await br.stop()
        return br, res

    br, res = run(go())
    assert br.stats.c["rx_lt2_report"] == 5 and br.stats.c["lt_rx_rate_limited"] == 15
    assert br.stats.c["lt_rx_duplicate"] == 1
    assert br.stats.c["rx_rate_limited"] == 1  # the 4th road frame, by the general limit only


def test_first_hop_limit_one_noisy_repeater_does_not_block_others():
    """Shared scenario with LimitsTest.kt: defaults rx_per_min 30, rx_per_first_hop_per_min 10."""
    async def go():
        br = make()
        await br.start()
        for i in range(12):  # all entered the mesh through repeater 0x30
            await br.on_datagram(heard(report(1500 + i), 2, path=[b"\x30", b"\x21"]))
        for i in range(3):   # another first hop, same last hop
            await br.on_datagram(heard(report(1520 + i), 2, path=[b"\x31", b"\x21"]))
        for i in range(3):   # heard directly: its own key
            await br.on_datagram(heard(report(1530 + i), 0))
        await br.on_datagram(heard(report(1500), 2, path=[b"\x30", b"\x21"]))  # a copy is never limited
        await br.drain()
        st = br.status()
        await br.stop()
        return br, st

    br, st = run(go())
    assert br.cfg.libretag.rx_per_first_hop_per_min == 10 and br.cfg.libretag.rx_per_min == 30
    assert br.stats.c["lt_rx_first_hop_limited"] == 2
    assert br.stats.c["rx_lt2_report"] == 16 and br.stats.c["lt_rx_rate_limited"] == 0
    assert st["counters"]["lt_rx_first_hop_limited"] == 2 and st["total"]["dropped"] >= 2


def test_first_hop_limit_skipped_without_a_path():
    """An adapter without a path (companion serial, hop count unknown) only has the global limit."""
    async def go():
        br = make(rx_per_min=14)
        await br.start()
        for i in range(15):
            await br.on_datagram(Datagram(*LT, report(1600 + i), None, 8.0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.stats.c["lt_rx_first_hop_limited"] == 0
    assert br.stats.c["rx_lt2_report"] == 14 and br.stats.c["lt_rx_rate_limited"] == 1


def test_first_hop_key():
    from nostrmesh.lt2svc import first_hop_key

    assert first_hop_key(Datagram(*LT, b"", 0, path=[])) == ("direct",)
    assert first_hop_key(Datagram(*LT, b"", 2, path=[b"\x30\x01", b"\x21\x02"], path_hash_size=2)) == (2, b"\x30\x01")
    assert first_hop_key(Datagram(*LT, b"", 3)) is None
    assert first_hop_key(Datagram(*LT, b"", None)) is None


def test_status_keys():
    """The Android bridge writes the same status.json keys (ReliabilityTest.HOST_STATUS_KEYS)."""
    st = make().status()
    assert set(st) == {"at", "started_at", "interval_s", "total", "last_hour", "airtime_window_s", "airtime_cap_s",
                       "radio_silence_s", "radio", "mailbox_tags", "outbox", "counters"}
    assert set(st["total"]) == set(st["last_hour"]) == {"heard", "published", "dropped", "spam_rejected",
                                                         "sent_to_radio", "airtime_s"}


def test_a5_full_mailbox_evicts_oldest_and_relay_quota():
    async def go():
        br = make(mailbox_cap=6, mailbox_relay_share=0.5)
        await br.start()
        evs = []
        for i, relay in enumerate(["wss://a", "wss://a", "wss://b", "wss://b", "wss://c", "wss://c", "wss://b",
                                   "wss://a", "wss://a"]):
            ev = pow_event(command(800 + i), plain_tags([report(800 + i)[:4]]))
            evs.append(ev)
            await br.on_relay_event(ev.to_dict(), relay)
            br.clock_ref["t"] += 1
        await br.stop()
        return br, evs

    br, evs = run(go())
    kept = [r[0] for r in br.ledger._all("SELECT event_id FROM mailbox ORDER BY known_at")]
    rel = dict(br.ledger._all("SELECT relay, COUNT(*) FROM mailbox GROUP BY relay"))
    assert len(kept) == 6 and all(n <= 3 for n in rel.values())
    # the oldest go first, and no relay holds more than half
    assert kept == [evs[i].id for i in (3, 4, 5, 6, 7, 8)]


def test_a6_identical_payload_goes_out_once():
    p, q = report(560), report(561)
    pc = command(560)

    async def go():
        from test_lt2bridge import timed_event
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.on_datagram(heard(q, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        # the same Pc in entries for other times and another via (a stranger's copies would not even lock)
        evs = [timed_event(pc, xc(80), now + 300, p[:4], created_at=now),
               timed_event(pc, xc(81), now + 400, q[:4], created_at=now),
               timed_event(pc, xc(82), now + 2000, p[:4], created_at=now)]
        for ev in evs:
            await br.on_relay_event(ev.to_dict(), "wss://a")
        for ev in evs:
            br.clock_ref["t"] = int(next(t[1] for t in ev.tags if t[0] == "at")) - 0.05
            br.lt2.timed_tick(br.clock_ref["t"])
            await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc]
    assert br.stats.c["lt_cmd_dup_payload"] == 2


def test_a6_untimed_copy_of_a_sent_payload():
    p1, p2 = report(565), report(566)
    pc = command(565)

    async def go():
        br = make()
        await br.start()
        from test_lt2bridge import mailbox_event
        await br.on_relay_event(mailbox_event(pc, [p1[:4]]).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(pc, [p2[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p1, 0))
        await br.drain()
        await br.on_datagram(heard(p2, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert [d.payload for d in br.radio.sent] == [pc] and br.stats.c["lt_cmd_dup_payload"] == 1


# ---------------------------------------------------------------- C: command groups


G = "0123456789abcdef"


def group_setup(br, now, p_n, next_ys, n_checks=4, g=G, first_at=900):
    from test_lt2bridge import timed_event  # noqa: F401
    evs = []
    for i in range(n_checks):
        tags = timed_tags(xc(90 + i), now + first_at * (i + 1), p_n[:4], g, created_at=now)
        evs.append(sign_event(owner_sk(p_n[:4]), now, 4244, tags, base64.b64encode(command(900 + i)).decode()))
    if next_ys:
        evs.append(sign_event(owner_sk(next_ys[0]), now, 4244, plain_tags(next_ys, g, created_at=now),
                              base64.b64encode(command(950)).decode()))
    return evs


def claims(br, done=False):
    return [e for e in br.pool.published if e["kind"] == lt2.NOSTR_CLAIM_KIND and
            any(t[0] == "done" for t in e["tags"]) == done]


def test_group_closed_by_the_trackers_next_report():
    p_n, p_n1 = report(1000), report(1001)

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p_n, 3))  # over 3 hops: timed sends flood (no path, no fallback)
        await br.drain()
        now = int(br.clock_ref["t"])
        for ev in group_setup(br, now, p_n, [p_n1[:4], report(1001, 1)[:4], report(1002)[:4]]):
            await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = now + 900 - 0.05            # check 1: the bridge sends
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        br.clock_ref["t"] = now + 960                   # the tracker executed it and sent n+1 (CMD_ACK)
        await br.on_datagram(heard(p_n1, 3))
        await br.drain()
        for i in range(2, 5):
            br.clock_ref["t"] = now + 900 * i - 0.05
            br.lt2.timed_tick(br.clock_ref["t"])
            await br.drain()
        await br.stop()
        return br

    br = run(go())
    sent = [d.payload for d in br.radio.sent]
    assert sent == [command(900), command(950)]  # check 1, then the plain entry still answers n+1
    assert br.stats.c["lt_group_answered"] == 1
    d = claims(br, done=True)
    assert len(d) == 1 and d[0]["tags"] == [["g", G], ["done", p_n1[:4].hex()]]
    assert br.ledger.group_done(G)


def test_group_not_closed_by_copies_of_report_via():
    p_n = report(1010)
    copy = report(1010, 1)  # j = 1 of the same report, a few seconds later

    async def go():
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p_n, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        for ev in group_setup(br, now, p_n, [p_n[:4], copy[:4]], n_checks=2):  # the entry for n names its copies
            await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = now + 8
        await br.on_datagram(heard(copy, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert not br.ledger.group_done(G) and br.stats.c["lt_group_answered"] == 0


def test_done_claim_needs_own_evidence():
    p_n, p_n1 = report(1030), report(1031)

    async def go(evidence):
        br = make(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_datagram(heard(p_n, 3))
        await br.drain()
        now = int(br.clock_ref["t"])
        for ev in group_setup(br, now, p_n, [p_n1[:4]], n_checks=3):
            await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = now + 950
        if evidence:  # another bridge published report n+1
            rep = sign_event(k(700), now + 940, lt2.NOSTR_REPORT_KIND, [["y", p_n1[:4].hex()]],
                             base64.b64encode(p_n1).decode())
            await br.on_relay_event(rep.to_dict(), "wss://b")
        done = sign_event(k(701), now + 950, lt2.NOSTR_CLAIM_KIND,
                          [["g", G], ["done", p_n1[:4].hex()], ["at", str(now + 900)]], "")
        await br.on_relay_event(done.to_dict(), "wss://b")
        for i in (2, 3):
            br.clock_ref["t"] = now + 900 * i - 0.05
            br.lt2.timed_tick(br.clock_ref["t"])
            await br.drain()
        await br.stop()
        return br

    br = run(go(False))
    assert not br.ledger.group_done(G) and br.stats.c["lt_group_done_claim_ignored"] == 1
    assert len(br.radio.sent) == 2
    br = run(go(True))
    assert br.ledger.group_done(G) and br.stats.c["lt_group_closed_by_claim"] == 1 and br.radio.sent == []


def test_late_entries_of_a_closed_group_not_added():
    p_n, p_n1 = report(1040), report(1041)

    async def go():
        br = make()
        await br.start()
        await br.on_datagram(heard(p_n, 0))
        await br.drain()
        now = int(br.clock_ref["t"])
        evs = group_setup(br, now, p_n, [p_n1[:4]], n_checks=3)
        for ev in evs[:1] + evs[-1:]:
            await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = now + 300
        await br.on_datagram(heard(p_n1, 0))
        await br.drain()
        for ev in evs[1:3]:  # from a slow relay
            await br.on_relay_event(ev.to_dict(), "wss://c")
        await br.stop()
        return br

    br = run(go())
    assert br.stats.c["lt_group_done_before"] == 2


def test_bad_group_tag_rejected():
    async def go():
        br = make()
        await br.start()
        ev = sign_event(k(710), T0, 4244, plain_tags([report(1050)[:4]], "XYZ"), base64.b64encode(command(1050)).decode())
        await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.stop()
        return br

    assert run(go()).stats.c["lt_mailbox_bad"] == 1


# ---------------------------------------------------------------- stale frames, outbox, watchdog, status


def test_stale_companion_frame_published_approximate_never_triggers_command():
    p = report(1100)

    async def go():
        br = make()
        await br.start()
        from test_lt2bridge import mailbox_event
        await br.on_relay_event(mailbox_event(command(1100), [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 600
        dg = Datagram(*LT, p, 0, 5.0, path=[], stale=True, rx_time_min=T0 + 100)
        await br.on_datagram(dg)
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert br.radio.sent == [] and br.stats.c["lt_rx_stale"] == 1
    ev = [e for e in br.pool.published if e["kind"] == lt2.NOSTR_REPORT_KIND][0]
    # created_at = the drain (the latest possible time, so a `since` query still sees it),
    # ["approx", earliest] = the last time the queue was seen empty
    assert ev["created_at"] == T0 + 600
    assert ["approx", str(T0 + 100)] in ev["tags"]
    assert lt2.report_time(ev["created_at"], ev["tags"]) == (T0 + 100, T0 + 600)


def test_stale_frame_without_a_known_empty_queue_is_approximate_at_the_drain():
    p = report(1101)

    async def go():
        br = make()
        await br.start()
        br.clock_ref["t"] += 600
        await br.on_datagram(Datagram(*LT, p, 0, 5.0, path=[], stale=True))
        await br.drain()
        await br.stop()
        return br

    ev = [e for e in run(go()).pool.published if e["kind"] == lt2.NOSTR_REPORT_KIND][0]
    assert ev["created_at"] == T0 + 600 and ["approx", str(T0 + 600)] in ev["tags"]


def test_approx_report_on_relay_counts_with_its_earliest_time():
    p = report(1102)
    y = p[:4].hex()

    async def go():
        br = make()
        await br.start()
        ev = sign_event(k(9), T0, lt2.NOSTR_REPORT_KIND, [["y", y], ["approx", str(T0 - 900)]],
                        base64.b64encode(p).decode())
        await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.stop()
        return br

    assert run(go()).lt2.relay_reports[y] == T0 - 900


class FlakyPool(Pool):
    def __init__(self):
        super().__init__()
        self.up = False

    async def publish(self, ev, retry=True):
        self.published.append(ev)
        return {"wss://a": (self.up, "" if self.up else "error: not connected")}


def test_outbox_keeps_unpublished_events_across_restart(tmp_path):
    db = tmp_path / "l.sqlite"
    p = report(1110)

    async def go():
        br = make(ledger=Ledger(db))
        br.pool = FlakyPool()
        await br.start()
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        led = Ledger(db)
        assert led.outbox_size() == 1
        br2 = make(ledger=led, now=T0 + 3 * 86400)  # three days later the relays are back
        br2.pool = FlakyPool()
        br2.pool.up = True
        await br2.start()
        await br2.tick()
        await br2.drain()
        await br2.stop()
        return br, br2

    br, br2 = run(go())
    assert br.stats.c["publish_deferred"] == 1
    assert br2.stats.c["outbox_published"] == 1 and br2.ledger.outbox_size() == 0
    assert br2.pool.published[0]["id"] == br.pool.published[0]["id"]  # the same signed event, not a new one
    row = br2.ledger.get(lt2_key(p))
    assert row["published_at"] is not None


def lt2_key(p):
    from nostrmesh.lt2svc import report_key
    return report_key(p)


def test_outbox_drops_expired_events(tmp_path):
    async def go():
        br = make()
        br.pool = FlakyPool()
        await br.start()
        await br.on_datagram(heard(report(1120), 0))
        await br.drain()
        br.clock_ref["t"] += 15 * 86400
        await br.tick()
        await br.stop()
        return br

    br = run(go())
    assert br.ledger.outbox_size() == 0 and len(br.pool.published) == 1


def test_radio_silence_is_radio_down_without_exit():
    """R37: nothing from the radio for rx_silence_s makes the radio down (announced, hooks); the bridge never exits."""
    async def go():
        br = make()
        br.cfg.watchdog.rx_silence_s = 600
        await br.start()
        br.clock_ref["t"] += 500
        await br.health.tick(br.clock_ref["t"])
        first = br.health.state
        await br.on_datagram(heard(report(1130), 0))  # a frame resets it
        br.clock_ref["t"] += 500
        await br.health.tick(br.clock_ref["t"])
        second = br.health.state
        br.clock_ref["t"] += 700
        await br.health.tick(br.clock_ref["t"])
        third = br.health.state
        await br.drain()
        await br.stop()
        return first, second, third, br

    first, second, third, br = run(go())
    assert (first, second, third) == ("up", "up", "down")
    assert br.health.reason == "rx_silence" and not br.fatal.is_set()



def test_watchdog_counts_adapter_activity():
    async def go():
        radio = MemoryDirectRadio()
        br = make(radio=radio)
        br.cfg.watchdog.rx_silence_s = 600
        await br.start()
        br.clock_ref["t"] += 700
        radio.last_activity = br.clock_ref["t"] - 10  # serial answers to the poll: the link lives
        await br.tick()
        await br.stop()
        return br

    assert not run(go()).fatal.is_set()


def test_heartbeat_status_file_and_cli(tmp_path, capsys):
    from nostrmesh import cli

    path = tmp_path / "status.json"

    async def go():
        br = make()
        br.cfg.status.path = str(path)
        await br.start()
        await br.on_datagram(heard(report(1140), 0))
        await br.drain()
        br.clock_ref["t"] += 3601
        await br.tick()
        await br.stop()
        return br

    br = run(go())
    st = json.loads(path.read_text())
    assert st["last_hour"]["heard"] == 1 and st["last_hour"]["published"] == 1
    assert st["total"]["spam_rejected"] == 0 and "libretag" in st["airtime_cap_s"]
    st["at"] = int(__import__("time").time())
    path.write_text(json.dumps(st))
    with pytest.raises(SystemExit) as e:
        cli.main(["status", "--path", str(path)])
    assert e.value.code == 0 and "heard 1" in capsys.readouterr().out
    st["at"] -= 3 * 3600
    path.write_text(json.dumps(st))
    with pytest.raises(SystemExit) as e:
        cli.main(["status", "--path", str(path)])
    assert e.value.code == 1 and "STALE" in capsys.readouterr().out


# ---------------------------------------------------------------- adapters


def test_companion_reconnects_and_marks_first_drain_stale():
    import time

    from test_nostrmesh import FakeSerial
    from nostrmesh.radio import CompanionSerial

    class Dying(FakeSerial):
        dead = False

        def read(self, n):
            if self.dead:
                raise OSError("device disconnected")
            return super().read(n)

    nodes = []

    def factory():
        f = Dying(["Public", "#libretag"])
        if nodes:  # the node queued this while the bridge was away
            f.queue.append(bytes([0x1B, 8, 0, 0, 1, 0, 0x4C, 0xFF, 3]) + b"old")
        nodes.append(f)
        return f

    got = []
    cs = CompanionSerial("x", ["#libretag"], poll_s=0.05, ser_factory=factory)
    cs.reopen_backoff_s = (0.05,)
    cs.start(got.append)
    for _ in range(200):
        if cs.last_drained:
            break
        time.sleep(0.01)
    nodes[0].queue.append(bytes([0x1B, 8, 0, 0, 1, 0, 0x4C, 0xFF, 3]) + b"new")  # live: pushed and drained
    cs._wake.set()
    for _ in range(200):
        if got:
            break
        time.sleep(0.01)
    nodes[0].dead = True
    for _ in range(300):
        if len(nodes) > 1:
            break
        time.sleep(0.01)
    for _ in range(300):
        if len(got) > 1:
            break
        time.sleep(0.01)
    cs.close()
    assert [g.payload for g in got] == [b"new", b"old"]
    assert got[0].stale is False and got[1].stale is True and got[1].rx_time_min is not None
    assert cs.reconnects == 1


def test_kiss_thread_survives_link_errors():
    import time

    from nostrmesh.radio import KissRadio

    class Link:
        def __init__(self, broken):
            self.n, self.broken = 0, broken

        def receive(self, timeout=1.0):
            self.n += 1
            if self.broken:
                raise OSError("unplugged")
            time.sleep(0.01)
            return []

        def send(self, raw):
            pass

    links = []

    def factory():
        links.append(Link(broken=not links))
        return links[-1]

    kr = KissRadio("x", ["#libretag"], link_factory=factory)
    kr.reopen_backoff_s = (0.01,)
    kr.start(lambda dg: None)
    for _ in range(200):
        if len(links) > 1 and links[-1].n > 2:
            break
        time.sleep(0.01)
    kr.close()
    assert len(links) == 2 and kr.reconnects == 1


def test_nip42_auth_then_publish():
    from websockets.asyncio.server import serve

    from nostrmesh.relays import RelayPool, auth_event_signer

    sk = k(720)
    seen = {}

    async def handler(ws):
        await ws.send(json.dumps(["AUTH", "chal-123"]))
        authed = False
        async for raw in ws:
            msg = json.loads(raw)
            if msg[0] == "AUTH":
                ev = Event.from_dict(msg[1])
                ok = ev.verify() and ev.kind == 22242 and ["challenge", "chal-123"] in ev.tags
                authed = ok
                seen["auth"] = ev
                await ws.send(json.dumps(["OK", ev.id, ok, ""]))
            elif msg[0] == "EVENT":
                if not authed:
                    await ws.send(json.dumps(["OK", msg[1]["id"], False, "auth-required: please"]))
                else:
                    await ws.send(json.dumps(["OK", msg[1]["id"], True, ""]))

    async def go():
        async with serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            url = f"ws://127.0.0.1:{port}"
            pool = RelayPool([url], retry_delays=(), auth_signer=auth_event_signer(sk))
            pool.start()
            ev = sign_event(k(721), T0, 1, [], "hi")
            res = await pool.publish(ev.to_dict())
            await pool.stop()
            return res, url

    res, url = run(go())
    assert list(res.values()) == [(True, "")]
    assert ["relay", url] in seen["auth"].tags and seen["auth"].pubkey == secp.pubkey(sk).hex()

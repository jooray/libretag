"""Signed mailbox (PROTOCOL.md 4.2): a heard report commits to the mailbox key pk_n, and only events from a
key that matches and was at the bridge anchor_lead_s before the report ever go out."""
import base64

from libretag import lt2
from nostrmesh.nostr import sign_event
from test_lt2bridge import (KEYS, T0, anchor_event, command, heard, k, make, mailbox_event, owner_sk, report, run,
                            timed_event, xc)

LEAD = 60.0


def strict(**lt):
    return make(auto_anchor=False, anchor_lead_s=LEAD, **lt)


def sent(br):
    return [d.payload for d in br.radio.sent]


def test_anchor_must_come_anchor_lead_before_the_report():
    async def go(lead_s):
        p, pc = report(700), command(700)
        br = strict()
        await br.start()
        await br.on_relay_event(anchor_event(700).to_dict(), "wss://a")
        br.clock_ref["t"] += lead_s - 5
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 5
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go(LEAD + 1))
    assert sent(br) == [command(700)] and br.stats.c["lt_lock"] == 1
    br = run(go(LEAD - 30))  # anchor only 30 s ahead: someone near the tag could have fitted a key by then
    assert sent(br) == [] and br.stats.c["lt_lock_none"] == 1 and br.stats.c["lt_cmd_unsigned"] == 1


def test_an_untimed_entry_is_its_own_anchor():
    p, pc = report(705), command(705)

    async def go():
        br = strict()
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 1))
        await br.drain()
        await br.stop()
        return br

    assert sent(run(go())) == [pc]


def test_a_stranger_copying_the_y_tags_never_gets_on_air():
    p, pc = report(710), command(710)
    junk = bytes(29)

    async def go():
        br = strict()
        await br.start()
        await br.on_relay_event(anchor_event(710).to_dict(), "wss://a")
        # a stranger copies the owner's y tags (public in the anchor) into events of his own
        await br.on_relay_event(mailbox_event(junk, [p[:4]], sk=k(90), created_at=T0 + 30).to_dict(), "wss://b")
        await br.on_relay_event(anchor_event(710, sk=k(91)).to_dict(), "wss://b")
        br.clock_ref["t"] += LEAD + 1
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        # after the lock, nothing from another key is even stored for this report
        await br.on_relay_event(mailbox_event(bytes([1]) * 29, [p[:4]], sk=k(92)).to_dict(), "wss://b")
        await br.stop()
        return br

    br = run(go())
    assert sent(br) == [pc]
    assert br.ledger.locked(p[:4].hex()) == {lt2.mailbox_pk(KEYS.cmd(lt2.epoch(710)), 710).hex()}
    # the stranger's earlier entry stays stored (a variant of P with another c could lock his key), unsent;
    # the one after the lock is refused on arrival
    assert br.stats.c["lt_mailbox_unsigned"] == 1


def test_a_view_share_cannot_sign():
    """A view share has V_e: it computes x and can publish under y, but its keys (anything but sk_n,
    which needs C_e) never fit c."""
    p, pc = report(715), command(715)
    view_sk = lt2.mailbox_sk(KEYS.view(lt2.epoch(715)), 715)  # the closest a view holder can get

    async def go():
        br = strict()
        await br.start()
        await br.on_relay_event(mailbox_event(pc, [p[:4]], sk=view_sk).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert sent(br) == [] and br.stats.c["lt_lock_none"] == 1


def test_late_entry_from_the_locked_key_goes_out_at_once():
    p, pc = report(720), command(720)

    async def go(delay, sk=None):
        br = strict()
        await br.start()
        await br.on_relay_event(anchor_event(720).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        br.clock_ref["t"] += delay
        await br.on_relay_event(mailbox_event(pc, [p[:4], report(720, 1)[:4]], created_at=int(br.clock_ref["t"]),
                                              sk=sk).to_dict(), "wss://a")
        await br.drain()
        await br.stop()
        return br

    br = run(go(30))
    assert sent(br) == [pc] and br.stats.c["lt_cmd_late"] == 1 and br.stats.c["lt_cmd_sent_zero-hop"] == 1
    assert sent(run(go(LEAD + 5))) == []          # the tag stopped listening
    br = run(go(30, sk=k(93)))                     # a stranger's late entry
    assert sent(br) == [] and br.stats.c["lt_cmd_late"] == 0


def test_timed_entries_only_from_the_key_report_via_locked():
    p = report(730)

    async def go():
        br = strict(timed_claim_lead_s=0.05)
        await br.start()
        await br.on_relay_event(anchor_event(730).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 2))
        await br.drain()
        now = int(br.clock_ref["t"])
        junk = timed_event(bytes(29), xc(30), now + 600, p[:4], created_at=now, sk=k(94))
        good = timed_event(command(731), xc(31), now + 900, p[:4], created_at=now)
        for ev in (junk, good):
            await br.on_relay_event(ev.to_dict(), "wss://a")
        br.clock_ref["t"] = now + 900 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert sent(br) == [command(731)] and br.stats.c["lt_mailbox_unsigned"] == 1


def test_timed_entry_stored_before_its_report_needs_the_lock_when_due():
    p = report(735)

    async def go(anchored):
        br = strict(timed_claim_lead_s=0.05)
        await br.start()
        now = int(br.clock_ref["t"])
        if anchored:
            await br.on_relay_event(anchor_event(735).to_dict(), "wss://a")
        await br.on_relay_event(timed_event(command(736), xc(35), now + 3600, p[:4], created_at=now).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 1))
        await br.drain()
        br.clock_ref["t"] = now + 3600 - 0.05
        br.lt2.timed_tick(br.clock_ref["t"])
        await br.drain()
        await br.stop()
        return br

    assert sent(run(go(True))) == [command(736)]
    br = run(go(False))
    assert sent(br) == [] and br.stats.c["lt_cmd_unsigned"] == 1


def test_a_changed_c_adds_a_key_but_never_removes_the_owners():
    """Someone with a radio re-sends P with another c that fits his own key, posted under the owner's y
    before the report. Both keys lock; the owner's command still goes out, his junk once."""
    p, pc = report(740), command(740)
    att = k(95)
    from nostrmesh import secp
    att_pk = secp.pubkey(att)
    forged = p[:4] + lt2.report_commit(att_pk, p) + p[7:]
    junk = bytes([7]) * 29

    async def go(order):
        br = strict()
        await br.start()
        await br.on_relay_event(anchor_event(740).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(junk, [p[:4]], sk=att).to_dict(), "wss://b")
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        for pkt in order:
            await br.on_datagram(heard(pkt, 0))
            await br.drain()
        await br.stop()
        return br

    for order in ((forged, p), (p, forged)):
        br = run(go(order))
        assert sorted(sent(br)) == sorted([pc, junk])
        assert lt2.mailbox_pk(KEYS.cmd(lt2.epoch(740)), 740).hex() in br.ledger.locked(p[:4].hex())


def test_relay_copy_of_the_report_moves_the_deadline_back():
    """A kind 4243 of the report on a relay before the radio heard it: anchor_lead_s counts from there."""
    p, pc = report(745), command(745)

    async def go(relay_first_by):
        br = strict()
        await br.start()
        await br.on_relay_event(anchor_event(745).to_dict(), "wss://a")
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 10
        if relay_first_by:
            t = int(br.clock_ref["t"] - relay_first_by)
            rep = sign_event(k(96), t, lt2.NOSTR_REPORT_KIND, [["y", p[:4].hex()]], base64.b64encode(p).decode())
            await br.on_relay_event(rep.to_dict(), "wss://c")
        await br.on_datagram(heard(p, 3))
        await br.drain()
        await br.stop()
        return br

    assert sent(run(go(0))) == [pc]
    assert sent(run(go(20))) == []  # the report was public 20 s before the radio heard it: lead only 50 s


def test_keys_per_y_first_seen_stay():
    p, pc = report(750), command(750)

    async def go():
        br = strict(keys_per_y=3)
        await br.start()
        await br.on_relay_event(anchor_event(750).to_dict(), "wss://a")
        for i in range(4):
            await br.on_relay_event(anchor_event(750, sk=k(200 + i)).to_dict(), "wss://b")
        await br.on_relay_event(mailbox_event(bytes(29), [p[:4]], sk=k(210)).to_dict(), "wss://b")
        await br.on_relay_event(mailbox_event(pc, [p[:4]]).to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert len(br.ledger.keys_of_y(p[:4].hex())) == 3 and br.stats.c["lt_mailbox_y_full"] == 3
    assert sent(br) == [pc]


def test_anchor_alone_sends_nothing_and_deletion_by_the_same_key():
    p = report(755)

    async def go():
        br = strict()
        await br.start()
        a = anchor_event(755)
        await br.on_relay_event(a.to_dict(), "wss://a")
        br.clock_ref["t"] += LEAD + 1
        await br.on_datagram(heard(p, 0))
        await br.drain()
        await br.stop()
        return br

    br = run(go())
    assert sent(br) == [] and br.stats.c["lt_anchor_added"] == 1 and br.stats.c["lt_lock"] == 1


def test_bad_anchors_refused():
    async def go():
        br = strict()
        await br.start()
        ve = KEYS.view(lt2.epoch(760))
        y = lt2.report_tag(ve, 760, 0).hex()
        timed_anchor = sign_event(owner_sk(bytes.fromhex(y)), T0, lt2.NOSTR_COMMAND_KIND,
                                  [["v", "2"], ["y", y], ["at", str(T0 + 60)], ["via", y]], "")
        no_v = sign_event(owner_sk(bytes.fromhex(y)), T0, lt2.NOSTR_COMMAND_KIND, [["y", y]], "")
        for ev in (timed_anchor, no_v):
            await br.on_relay_event(ev.to_dict(), "wss://a")
        await br.stop()
        return br

    assert run(go()).stats.c["lt_mailbox_bad"] == 2

package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.unhex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * Signed mailbox (PROTOCOL.md 4.2), parity with host/tests/test_signed_mailbox.py: a heard report commits
 * to the mailbox key pk_n, and only events from a key that matches and was at the bridge anchor_lead
 * (60 s) before the report ever go out. Reports here are [Synth] reports: their commitment fits
 * [Synth.sk] like a real report fits sk_n.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignedMailboxTest {
    private val lt2 = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lt2.json")!!.readText()).jsonObject
    private val pc = lt2["commands"]!!.jsonArray[0].jsonObject["p"]!!.jsonPrimitive.content.unhex()!!
    private fun pcN(i: Int) = pc.copyOf().also { it[28] = i.toByte(); it[27] = (i shr 8).toByte() }

    private val t0 = 1_763_020_900_000L
    private val caps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)
    private val direct0 = RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1)
    private val hops2 = RxMeta(hops = 2, path = "aabb".unhex(), pathHashSize = 1)
    private val lead = 60_000L

    private class Rig(val b: Bridge, val m: FakeMesh, val r: FakeRelays, val clock: () -> Long)

    /** Default timing: anchor lead 60 s, late entries 60 s, no automatic anchors. */
    private fun TestScope.rig(cfg: BridgeConfig = BridgeConfig()): Rig {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        val clock = { t0 + testScheduler.currentTime }
        return Rig(Bridge(this, m, r, cfg, emptyList(), clock = clock, random = Random(7), newKey = keySource()), m, r, clock)
    }

    private fun Rig.nowS() = clock() / 1000
    private fun Rig.counter(k: String) = b.status()["counters"]!!.jsonObject[k]?.jsonPrimitive?.longOrNull ?: 0L
    private fun Rig.sent() = m.sent.map { it.payload.hex() }
    private suspend fun Bridge.hear(p: ByteArray, meta: RxMeta = direct0) =
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, meta)

    private fun anchor(i: Int, createdS: Long, sk: ByteArray = Synth.sk(i)) = NostrEvent.sign(sk, createdS, Lt2.KIND_COMMAND,
        listOf(listOf("v", "2"), listOf("y", Lt2.tag(Synth.report(i))), listOf("expiration", "${createdS + 26 * 3600}")), "")

    private fun entry(ys: List<String>, createdS: Long, payload: ByteArray = pc, sk: ByteArray? = null, at: Long? = null, via: String? = null) =
        ownerEntry(createdS, listOf(listOf("v", "2"), listOf("route", "flood"), listOf("expiration", "${createdS + 6 * 3600}")) +
            ys.map { listOf("y", it) } + listOfNotNull(at?.let { listOf("at", "$it") }, via?.let { listOf("via", it) }),
            Base64.getEncoder().encodeToString(payload), sk)

    @Test
    fun anchorMustComeAnchorLeadBeforeTheReport() = runTest {
        for ((aheadMs, sent) in listOf(lead + 1000 to true, lead - 30_000 to false)) {
            val g = rig()
            val p = Synth.report(700)
            g.b.onRelayEvent(anchor(700, g.nowS()).toJson())
            advanceTimeBy(aheadMs - 5000)
            g.b.onRelayEvent(entry(listOf(Lt2.tag(p)), g.nowS()).toJson())
            advanceTimeBy(5000)
            g.b.hear(p)
            advanceUntilIdle()
            assertEquals("ahead $aheadMs", if (sent) listOf(pc.hex()) else emptyList(), g.sent())
            assertEquals(if (sent) 1L else 0L, g.counter("lt_lock"))
            if (!sent) assertEquals(1L, g.counter("lt_cmd_unsigned"))
        }
    }

    @Test
    fun anUntimedEntryIsItsOwnAnchor() = runTest {
        val g = rig()
        val p = Synth.report(705)
        g.b.onRelayEvent(entry(listOf(Lt2.tag(p)), g.nowS()).toJson())
        advanceTimeBy(lead + 1000)
        g.b.hear(p, hops2)
        advanceUntilIdle()
        assertEquals(listOf(pc.hex()), g.sent())
    }

    @Test
    fun aStrangerCopyingTheYTagsNeverGetsOnAir() = runTest {
        val g = rig()
        val p = Synth.report(710)
        val y = Lt2.tag(p)
        g.b.onRelayEvent(anchor(710, g.nowS()).toJson())
        g.b.onRelayEvent(entry(listOf(y), g.nowS(), ByteArray(29), sk = testKey(90)).toJson())
        g.b.onRelayEvent(anchor(710, g.nowS(), sk = testKey(91)).toJson())
        advanceTimeBy(lead + 1000)
        g.b.onRelayEvent(entry(listOf(y), g.nowS()).toJson())
        advanceTimeBy(1000)
        g.b.hear(p)
        advanceUntilIdle()
        // after the lock, nothing from a key that could never lock is even stored
        g.b.onRelayEvent(entry(listOf(y), g.nowS(), pcN(1), sk = testKey(92)).toJson())
        assertEquals(listOf(pc.hex()), g.sent())
        assertEquals(setOf(Synth.pk(710)), g.b.mailbox.locked(y))
        assertEquals(1L, g.counter("lt_mailbox_unsigned"))
    }

    @Test
    fun aViewShareCannotSign() = runTest {
        // a view share has V_e: it can compute x, but not sk_n (it needs C_e); here the vector report n = 5
        val p = lt2["reports"]!!.jsonArray[0].jsonObject["p"]!!.jsonPrimitive.content.unhex()!!
        val n = Owner.nOf(Lt2.tag(p))!!
        val viewSk = Owner.skFromChain(Owner.view(n / 64), n)
        val g = rig()
        g.b.onRelayEvent(entry(listOf(Lt2.tag(p)), g.nowS(), sk = viewSk).toJson())
        advanceTimeBy(lead + 1000)
        g.b.hear(p)
        advanceUntilIdle()
        assertTrue(g.sent().isEmpty())
        assertEquals(1L, g.counter("lt_lock_none"))
        // the owner's own key does lock it
        val h = rig()
        h.b.onRelayEvent(entry(listOf(Lt2.tag(p)), h.nowS()).toJson())
        advanceTimeBy(lead + 1000)
        h.b.hear(p)
        advanceUntilIdle()
        assertEquals(listOf(pc.hex()), h.sent())
    }

    @Test
    fun lateEntryFromTheLockedKeyGoesOutAtOnce() = runTest {
        for ((delayMs, stranger, sent) in listOf(Triple(30_000L, false, true), Triple(lead + 5000, false, false), Triple(30_000L, true, false))) {
            val g = rig()
            val p = Synth.report(720)
            g.b.onRelayEvent(anchor(720, g.nowS()).toJson())
            advanceTimeBy(lead + 1000)
            g.b.hear(p)
            advanceUntilIdle()
            advanceTimeBy(delayMs)
            g.b.onRelayEvent(entry(listOf(Lt2.tag(p)), g.nowS(), sk = if (stranger) testKey(93) else null).toJson())
            advanceUntilIdle()
            val label = "delay $delayMs stranger $stranger"
            assertEquals(label, if (sent) listOf(pc.hex()) else emptyList(), g.sent())
            assertEquals(label, if (sent) 1L else 0L, g.counter("lt_cmd_late"))
            if (sent) assertEquals(Route.ZeroHop, g.m.sent.single().route)
        }
    }

    @Test
    fun timedEntriesOnlyFromTheKeyReportViaLocked() = runTest {
        val g = rig()
        val p = Synth.report(730)
        g.b.onRelayEvent(anchor(730, g.nowS()).toJson())
        advanceTimeBy(lead + 1000)
        g.b.hear(p, hops2)
        advanceUntilIdle()
        val n = g.nowS()
        g.b.onRelayEvent(entry(listOf("c0de0030"), n, ByteArray(29), sk = testKey(94), at = n + 600, via = Lt2.tag(p)).toJson())
        g.b.onRelayEvent(entry(listOf("c0de0031"), n, pcN(31), at = n + 900, via = Lt2.tag(p)).toJson())
        advanceTimeBy((n + 900) * 1000 - 5_000 - g.clock())
        g.b.timedTick()
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(pcN(31).hex()), g.sent())
        assertEquals(1L, g.counter("lt_mailbox_unsigned"))
    }

    @Test
    fun timedEntryStoredBeforeItsReportNeedsTheLockWhenDue() = runTest {
        for (anchored in listOf(true, false)) {
            val g = rig()
            val p = Synth.report(735)
            val n = g.nowS()
            if (anchored) g.b.onRelayEvent(anchor(735, n).toJson())
            g.b.onRelayEvent(entry(listOf("c0de0035"), n, pcN(36), at = n + 3600, via = Lt2.tag(p)).toJson())
            advanceTimeBy(lead + 1000)
            g.b.hear(p, hops2)
            advanceUntilIdle()
            advanceTimeBy((n + 3600) * 1000 - 5_000 - g.clock())
            g.b.timedTick()
            runCurrent()
            advanceUntilIdle()
            assertEquals("anchored $anchored", if (anchored) listOf(pcN(36).hex()) else emptyList(), g.sent())
            if (!anchored) assertEquals(1L, g.counter("lt_cmd_unsigned"))
        }
    }

    @Test
    fun aChangedCAddsAKeyButNeverRemovesTheOwners() = runTest {
        // someone with a radio re-sends P with another c fitting his own key, posted under the owner's y before
        val att = testKey(95)
        val junk = ByteArray(29) { 7 }
        for (forgedFirst in listOf(true, false)) {
            val g = rig()
            val p = Synth.report(740)
            val forged = p.copyOf().also { commitOf(NostrEvent.xonly(att), it).copyInto(it, 4) }
            val y = Lt2.tag(p)
            g.b.onRelayEvent(anchor(740, g.nowS()).toJson())
            g.b.onRelayEvent(entry(listOf(y), g.nowS(), junk, sk = att).toJson())
            g.b.onRelayEvent(entry(listOf(y), g.nowS()).toJson())
            advanceTimeBy(lead + 1000)
            for (pkt in if (forgedFirst) listOf(forged, p) else listOf(p, forged)) {
                g.b.hear(pkt)
                advanceUntilIdle()
            }
            assertEquals("forged first $forgedFirst", setOf(pc.hex(), junk.hex()), g.sent().toSet())
            assertTrue(Synth.pk(740) in g.b.mailbox.locked(y))
        }
    }

    @Test
    fun aRelayCopyOfTheReportMovesTheDeadlineBack() = runTest {
        for ((relayFirstByS, sent) in listOf(0L to true, 20L to false)) {
            val g = rig()
            val p = Synth.report(745)
            g.b.onRelayEvent(anchor(745, g.nowS()).toJson())
            g.b.onRelayEvent(entry(listOf(Lt2.tag(p)), g.nowS()).toJson())
            advanceTimeBy(lead + 10_000)
            if (relayFirstByS > 0) g.b.onRelayEvent(Lt2.reportEvent(p, g.nowS() - relayFirstByS, testKey(96)).toJson(), "wss://c")
            g.b.hear(p, hops2)
            advanceUntilIdle()
            assertEquals("relay first by $relayFirstByS s", if (sent) listOf(pc.hex()) else emptyList(), g.sent())
        }
    }

    @Test
    fun keysPerYFirstSeenStay() = runTest {
        val g = rig(BridgeConfig(keysPerY = 3))
        val p = Synth.report(750)
        val y = Lt2.tag(p)
        g.b.onRelayEvent(anchor(750, g.nowS()).toJson())
        for (i in 0 until 4) g.b.onRelayEvent(anchor(750, g.nowS(), sk = testKey(200 + i)).toJson())
        g.b.onRelayEvent(entry(listOf(y), g.nowS(), ByteArray(29), sk = testKey(210)).toJson())
        g.b.onRelayEvent(entry(listOf(y), g.nowS()).toJson())
        advanceTimeBy(lead + 1000)
        g.b.hear(p)
        advanceUntilIdle()
        assertEquals(3, g.b.mailbox.keysOfY(y).size)
        assertEquals(3L, g.counter("lt_mailbox_y_full"))
        assertEquals(listOf(pc.hex()), g.sent())
    }

    @Test
    fun anchorAloneSendsNothingAndBadAnchorsAreRefused() = runTest {
        val g = rig()
        val p = Synth.report(755)
        val y = Lt2.tag(p)
        g.b.onRelayEvent(anchor(755, g.nowS()).toJson())
        assertEquals(1L, g.counter("lt_anchor_added"))
        val timedAnchor = NostrEvent.sign(Synth.sk(755), g.nowS(), Lt2.KIND_COMMAND,
            listOf(listOf("v", "2"), listOf("y", y), listOf("at", "${g.nowS() + 60}"), listOf("via", y)), "")
        val noV = NostrEvent.sign(Synth.sk(755), g.nowS(), Lt2.KIND_COMMAND, listOf(listOf("y", y)), "")
        g.b.onRelayEvent(timedAnchor.toJson())
        g.b.onRelayEvent(noV.toJson())
        assertEquals(setOf(Synth.pk(755)), g.b.mailbox.keysOfY(y))
        advanceTimeBy(lead + 1000)
        g.b.hear(p)
        advanceUntilIdle()
        assertTrue(g.sent().isEmpty())
        assertEquals(1L, g.counter("lt_lock"))
    }

    @Test
    fun anchorsAndLocksSurviveARestart() = runTest {
        val g = rig()
        val p = Synth.report(760)
        val q = Synth.report(761)
        g.b.onRelayEvent(anchor(760, g.nowS()).toJson())
        g.b.onRelayEvent(anchor(761, g.nowS()).toJson())
        advanceTimeBy(lead + 1000)
        g.b.hear(p, hops2)
        advanceUntilIdle()
        val state = Json.decodeFromString(BridgeState.serializer(), Json.encodeToString(BridgeState.serializer(), g.b.exportState()))
        val h = rig()
        h.b.importState(state)
        assertEquals(setOf(Synth.pk(760)), h.b.mailbox.locked(Lt2.tag(p)))
        // the anchor of q survived: q locks after the restart
        h.b.onRelayEvent(entry(listOf(Lt2.tag(q)), h.nowS(), pcN(61)).toJson())
        advanceTimeBy(1000)
        h.b.hear(q)
        advanceUntilIdle()
        assertEquals(listOf(pcN(61).hex()), h.sent())
    }
}

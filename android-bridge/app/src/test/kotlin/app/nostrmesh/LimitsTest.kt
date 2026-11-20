package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Mailbox
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** Mailbox abuse limits and command groups (PROTOCOL.md 4; parity with host/tests/test_mailbox_limits.py). */
@OptIn(ExperimentalCoroutinesApi::class)
class LimitsTest {
    private val lt2 = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lt2.json")!!.readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun reportP(i: Int) = lt2["reports"]!!.jsonArray[i].jsonObject.s("p").unhex()!!
    private val pc = lt2["commands"]!!.jsonArray[0].jsonObject.s("p").unhex()!!
    private fun pcN(i: Int) = pc.copyOf().also { it[28] = i.toByte(); it[27] = (i shr 8).toByte() }

    private val t0 = 1_763_020_900_000L
    private val caps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)
    private val direct0 = RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1)
    private val grp = "0123456789abcdef"

    private class Rig(val b: Bridge, val m: FakeMesh, val r: FakeRelays, val clock: () -> Long)

    private fun TestScope.rig(cfg: BridgeConfig = BridgeConfig().legacyTiming()): Rig {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        val clock = { t0 + testScheduler.currentTime }
        return Rig(Bridge(this, m, r, cfg, emptyList(), clock = clock, random = Random(7), newKey = keySource()), m, r, clock)
    }

    private fun Rig.nowS() = clock() / 1000
    private suspend fun Bridge.hear(p: ByteArray, meta: RxMeta = direct0) {
        ownerAnchor(p, t0 / 1000)
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, meta)
    }

    /** The owner's entry ([key] only told the authors apart before the signed mailbox). */
    private fun plain(key: Int, createdS: Long, ys: List<String>, payload: ByteArray = pc, group: String? = null) =
        ownerEntry(createdS,
            ys.map { listOf("y", it) } + listOf(listOf("expiration", "${createdS + 48 * 3600}")) + listOfNotNull(group?.let { listOf("g", it) }),
            Base64.getEncoder().encodeToString(payload))

    private fun timedTags(y: String, at: Long, via: String, createdS: Long, group: String? = null) =
        listOf(listOf("y", y), listOf("expiration", "${createdS + 48 * 3600}"), listOf("at", "$at"), listOf("via", via)) +
            listOfNotNull(group?.let { listOf("g", it) })

    private fun timed(key: Int, createdS: Long, y: String, at: Long, via: String, payload: ByteArray = pc, group: String? = null) =
        ownerEntry(createdS, timedTags(y, at, via, createdS, group), Base64.getEncoder().encodeToString(payload))

    private fun y(i: Int) = "%08x".format(0xc0de0000.toInt() + i)

    /** Runs the timed scheduler at `at` (minus the claim lead) and lets the delivery finish. */
    private fun TestScope.fireAt(g: Rig, atS: Long) {
        advanceTimeBy(atS * 1000 - 5_000 - g.clock())
        g.b.timedTick()
        runCurrent()
        advanceUntilIdle()
    }

    @Test
    fun futureCreatedAtRefused() = runTest {
        val g = rig()
        val p = reportP(0); val x = Lt2.tag(p)
        g.b.hear(p)
        advanceUntilIdle()
        val n = g.nowS()
        g.b.onRelayEvent(timed(20, n, y(1), n + 200, x).toJson(), "wss://a")
        g.b.onRelayEvent(timed(23, n + 61, y(4), n + 200, x, pcN(4)).toJson(), "wss://a") // from the future
        assertEquals(1, g.b.mailbox.size)
    }

    // ------------------------------------------------------------------ A1 .. A6

    @Test
    fun atMustFollowReportViaWithin25h() = runTest {
        val g = rig()
        val p = reportP(0); val x = Lt2.tag(p)
        g.b.hear(p)
        advanceUntilIdle()
        val n = g.nowS()
        g.b.onRelayEvent(timed(30, n, y(1), n + 25 * 3600 + 10, x).toJson())
        g.b.onRelayEvent(timed(31, n, y(2), n + 24 * 3600, x, pcN(2)).toJson())
        assertEquals(1, g.b.mailbox.size)
    }

    @Test
    fun atMostTwelveTimedPerViaEarlierSeenStay() = runTest {
        val g = rig()
        val x = Lt2.tag(reportP(0))
        val n = g.nowS()
        val evs = (0 until 14).map { i -> timed(100 + i, n, y(i), n + 900L * (i + 1), x, pcN(i)) }
        for (ev in evs) { g.b.onRelayEvent(ev.toJson(), "wss://a"); advanceTimeBy(10) }
        val kept = g.b.mailbox.export().commands.map { it.id }.toSet()
        assertEquals(evs.take(12).map { it.id }.toSet(), kept)
    }

    @Test
    fun viaGapHourlyCapAndIdenticalPayload() = runTest {
        val g = rig(BridgeConfig(timedPerHour = 3).legacyTiming())
        val vias = (0 until 3).map { reportP(it) }
        vias.forEach { g.b.hear(it) }
        advanceUntilIdle()
        val n = g.nowS()
        val x = vias.map { Lt2.tag(it) }
        val evs = listOf(
            timed(40, n, y(1), n + 600, x[0], pcN(1)),
            timed(41, n, y(2), n + 900, x[0], pcN(2)),   // 5 min after the first, same via
            timed(42, n, y(3), n + 1300, x[0], pcN(3)),
            timed(43, n, y(4), n + 1400, x[1], pcN(1)),  // same Pc as the first: goes once only
            timed(44, n, y(5), n + 1500, x[1], pcN(5)),
            timed(45, n, y(6), n + 1700, x[2], pcN(6)),  // fourth transmission in the hour
        )
        evs.forEach { g.b.onRelayEvent(it.toJson()) }
        for (ev in evs) fireAt(g, ev.tags.first { it[0] == "at" }[1].toLong())
        assertEquals(listOf(pcN(1), pcN(3), pcN(5)).map { it.hex() }, g.m.sent.map { it.payload.hex() })
    }

    @Test
    fun libretagAirtimeIsSeparateAndPersisted() = runTest {
        val g = rig(BridgeConfig(ltAirtimeFraction = 0.00007).legacyTiming()) // 252 ms: one command at SF7
        val p0 = reportP(0); val p1 = reportP(1)
        g.b.onRelayEvent(plain(50, g.nowS(), listOf(Lt2.tag(p0)), pcN(1)).toJson())
        g.b.onRelayEvent(plain(51, g.nowS(), listOf(Lt2.tag(p1)), pcN(2)).toJson())
        advanceTimeBy(1000)
        g.b.hear(p0); advanceUntilIdle()
        g.b.hear(p1); advanceUntilIdle()
        assertEquals(1, g.m.sent.size)
        val state = Json.decodeFromString(BridgeState.serializer(), Json.encodeToString(BridgeState.serializer(), g.b.exportState()))
        assertTrue(state.ltAirtime.isNotEmpty() && state.airtime.isEmpty())
    }

    @Test
    fun libretagReceiveLimitIsOwn() = runTest {
        val g = rig(BridgeConfig(ltRxPerMinute = 2).legacyTiming())
        val ps = (0 until 3).map { reportP(it) }
        ps.forEach { g.b.hear(it) }
        g.b.hear(ps[0]) // a copy: never limited
        advanceUntilIdle()
        assertEquals(2, g.r.ofKind(Lt2.KIND_REPORT).size)
    }

    private fun synthReport(i: Int) = ByteArray(Lt2.REPORT_LEN) { (it * 13 + i).toByte() }.also { it[0] = i.toByte(); it[1] = (i shr 8).toByte() }

    /** Same scenario as test_first_hop_limit_one_noisy_repeater_does_not_block_others (host). */
    @Test
    fun firstHopLimitOneNoisyRepeaterDoesNotBlockOthers() = runTest {
        val cfg = BridgeConfig().legacyTiming()
        assertEquals(10, cfg.ltRxPerFirstHopPerMinute)
        assertEquals(30, cfg.ltRxPerMinute)
        val g = rig(cfg)
        val via30 = RxMeta(hops = 2, path = "3021".unhex(), pathHashSize = 1)
        val via31 = RxMeta(hops = 2, path = "3121".unhex(), pathHashSize = 1)
        (0 until 12).forEach { g.b.hear(synthReport(100 + it), via30) }
        (0 until 3).forEach { g.b.hear(synthReport(120 + it), via31) }
        (0 until 3).forEach { g.b.hear(synthReport(130 + it)) } // heard directly: its own key
        g.b.hear(synthReport(100), via30) // a copy is never limited
        advanceUntilIdle()
        assertEquals(16, g.r.ofKind(Lt2.KIND_REPORT).size)
        val counters = g.b.status()["counters"]!!.jsonObject
        assertEquals(2L, counters["lt_rx_first_hop_limited"]!!.jsonPrimitive.longOrNull)
        assertTrue(counters["lt_rx_rate_limited"] == null)
    }

    @Test
    fun firstHopLimitSkippedWithoutAPath() = runTest {
        val g = rig(BridgeConfig(ltRxPerMinute = 14).legacyTiming())
        (0 until 15).forEach { g.b.hear(synthReport(200 + it), RxMeta(hops = null)) }
        advanceUntilIdle()
        assertEquals(14, g.r.ofKind(Lt2.KIND_REPORT).size)
        val counters = g.b.status()["counters"]!!.jsonObject
        assertTrue(counters["lt_rx_first_hop_limited"] == null)
        assertEquals(1L, counters["lt_rx_rate_limited"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun firstHopKeys() {
        assertEquals("direct", app.nostrmesh.bridge.firstHopKey(direct0, caps))
        assertEquals("2:3001", app.nostrmesh.bridge.firstHopKey(RxMeta(hops = 2, path = "30012102".unhex(), pathHashSize = 2), caps))
        assertEquals(null, app.nostrmesh.bridge.firstHopKey(RxMeta(hops = 3), caps))
        assertEquals(null, app.nostrmesh.bridge.firstHopKey(RxMeta(hops = 2, path = "3021".unhex(), pathHashSize = 1), emptySet()))
    }

    @Test
    fun fullMailboxEvictsOldestAndRelayQuota() {
        val mb = Mailbox(cap = 4)
        fun c(i: Int, relay: String) =
            Mailbox.Command("e$i", listOf(y(i)), false, pc, 1000L + i, Long.MAX_VALUE, 1, relay = relay)
        val now = 2000L
        mb.add(c(0, "a"), now, relayShare = 0.5)
        mb.add(c(1, "a"), now, relayShare = 0.5)
        mb.add(c(2, "a"), now, relayShare = 0.5) // relay a over 2: its oldest (0) goes
        mb.add(c(3, "b"), now, relayShare = 0.5)
        mb.add(c(4, "c"), now, relayShare = 0.5)
        mb.add(c(5, "c"), now, relayShare = 0.5) // total over 4: the oldest overall (1) goes
        val ids = mb.export().commands.map { it.id }.toSet()
        assertEquals(setOf("e2", "e3", "e4", "e5"), ids)
        assertTrue(mb.relayTags("a") <= 2)
    }

    // ------------------------------------------------------------------ groups

    @Test
    fun groupClosedByTheTrackersNextReportAndDoneClaim() = runTest {
        val g = rig()
        val pn = reportP(0); val pn1 = reportP(1)
        g.b.hear(pn, RxMeta(hops = 2, path = "aabb".unhex(), pathHashSize = 1))
        advanceUntilIdle()
        val n = g.nowS()
        (0 until 3).forEach { i -> g.b.onRelayEvent(timed(60 + i, n, y(i), n + 900L * (i + 1), Lt2.tag(pn), pcN(i), grp).toJson()) }
        g.b.onRelayEvent(plain(70, n, listOf(Lt2.tag(pn1)), pcN(9), grp).toJson())
        fireAt(g, n + 900)
        assertEquals(1, g.m.sent.size)
        advanceTimeBy(60_000)
        g.b.hear(pn1, RxMeta(hops = 3, path = "aabbcc".unhex(), pathHashSize = 1))
        advanceUntilIdle()
        fireAt(g, n + 1800); fireAt(g, n + 2700)
        assertEquals(listOf(pcN(0), pcN(9)).map { it.hex() }, g.m.sent.map { it.payload.hex() })
        val done = g.r.ofKind(Lt2.KIND_CLAIM).single { c -> c.tags.any { it[0] == "done" } }
        assertEquals(listOf(listOf("g", grp), listOf("done", Lt2.tag(pn1))), done.tags)
    }

    @Test
    fun doneClaimNeedsOwnEvidence() = runTest {
        for (evidence in listOf(false, true)) {
            val r = rig()
            val pn = reportP(0); val pn1 = reportP(1)
            r.b.hear(pn)
            advanceUntilIdle()
            val n = r.nowS()
            (0 until 2).forEach { i -> r.b.onRelayEvent(timed(80 + i, n, y(i), n + 900L * (i + 1), Lt2.tag(pn), pcN(i), grp).toJson()) }
            r.b.onRelayEvent(plain(90, n, listOf(Lt2.tag(pn1)), pcN(9), grp).toJson())
            if (evidence) r.b.onRelayEvent(Lt2.reportEvent(pn1, n, testKey(91)).toJson(), "wss://b")
            val claim = NostrEvent.sign(testKey(92), n, Lt2.KIND_CLAIM, listOf(listOf("g", grp), listOf("done", Lt2.tag(pn1))), "")
            r.b.onRelayEvent(claim.toJson(), "wss://b")
            assertEquals(evidence, r.b.mailbox.groupDone(grp))
            assertEquals(if (evidence) 1 else 3, r.b.mailbox.size)
        }
    }

    @Test
    fun mailboxChangeCallbackFires() = runTest {
        val g = rig()
        var calls = 0
        g.b.onMailboxChange = { calls++ }
        g.b.onRelayEvent(plain(95, g.nowS(), listOf(y(1))).toJson())
        assertEquals(1, calls)
    }
}

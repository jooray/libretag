package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Mailbox
import app.nostrmesh.libretag.ReportPaths
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** BLE packets as kind 4243, 0.7 timed mailbox entries and NIP-09 cancel (parity with lt2svc.py, ledger.py). */
@OptIn(ExperimentalCoroutinesApi::class)
class Lt2TimedTest {
    private val vectors = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lt2.json")!!.readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private val p = vectors["reports"]!!.jsonArray[0].jsonObject.s("p").unhex()!!
    private val x = Lt2.tag(p)
    private val pc = vectors["commands"]!!.jsonArray[0].jsonObject.s("p").unhex()!!
    private val ble = vectors["ble"]!!.jsonArray[0].jsonObject["packets"]!!.jsonArray[0].jsonObject.s("p").unhex()!!
    private val cmdY = "c0ffee01"

    private val t0 = 1_763_020_900_000L
    private val pathCaps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)

    private class Rig(val b: Bridge, val m: FakeMesh, val r: FakeRelays, val clock: () -> Long)

    private fun TestScope.rig(caps: Set<String> = pathCaps, cfg: BridgeConfig = BridgeConfig()): Rig {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        val clock = { t0 + testScheduler.currentTime }
        m.clock = clock
        return Rig(Bridge(this, m, r, cfg.legacyTiming(), emptyList(), clock = clock, random = Random(7), newKey = keySource()), m, r, clock)
    }

    private fun Rig.nowS() = clock() / 1000

    private fun command(nowS: Long, ys: List<String> = listOf(x), at: Long? = null, via: String? = null, key: Int = 50, flood: Boolean = false,
                        g: String? = null) =
        ownerEntry(nowS,
            ys.map { listOf("y", it) } + listOf(listOf("route", if (flood) "flood" else "near"), listOf("expiration", "${nowS + 48 * 3600}")) +
                listOfNotNull(at?.let { listOf("at", "$it") }, via?.let { listOf("via", it) }, g?.let { listOf("g", it) }),
            Base64.getEncoder().encodeToString(pc), if (key == 50) null else testKey(key))

    /** key 50: the owner (sk_n of the vector report, which every owner entry here belongs to). */
    private fun deletion(ids: List<String>, nowS: Long, key: Int = 50, k: String? = "4244") =
        NostrEvent.sign(if (key == 50) Owner.sk(Owner.nOf(x)!!) else testKey(key), nowS, Lt2.KIND_DELETION, ids.map { listOf("e", it) } + listOfNotNull(k?.let { listOf("k", it) }), "")

    private suspend fun Bridge.hear(payload: ByteArray, meta: RxMeta) {
        ownerAnchor(payload, t0 / 1000)
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, payload, meta)
    }

    private val twoHops = RxMeta(hops = 2, path = "aabb".unhex(), pathHashSize = 1)
    private val direct0 = RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1)

    // ------------------------------------------------------------------ BLE packets and expiry

    @Test
    fun blePacketGoesUpAsKind4243ExpiringIn14Days() = runTest {
        val g = rig()
        assertEquals(Lt2.BLE_LEN, ble.size)
        g.b.hear(ble, twoHops)
        g.b.hear(ble, direct0) // second copy: once
        g.b.hear(ByteArray(100), twoHops) // other lengths: ignored
        advanceUntilIdle()
        val ev = g.r.ofKind(Lt2.KIND_REPORT).single()
        assertTrue(ev.verify())
        assertEquals(listOf("y", Lt2.tag(ble)), ev.tags[0])
        assertEquals(ev.createdAt + 14 * 86400, ev.tags[1][1].toLong())
        assertEquals(ble.hex(), Base64.getDecoder().decode(ev.content).hex())
        assertEquals(0, g.m.sent.size)
    }

    // ------------------------------------------------------------------ timed entries

    @Test
    fun timedEntryGoesOutAtAtByFloodWithEarlyClaim() = runTest {
        val g = rig()
        g.b.hear(p, twoHops)
        advanceUntilIdle()
        val at = g.nowS() + 30
        g.b.onRelayEvent(command(g.nowS(), ys = listOf(cmdY), at = at, via = x, g = "00112233445566ff").toJson())
        assertEquals(1, g.b.mailbox.size)
        g.b.timedTick()
        runCurrent()
        assertEquals("not due yet", 0, g.r.ofKind(Lt2.KIND_CLAIM).size)
        advanceTimeBy(at * 1000 - 5_000 - g.clock()) // the claim lead
        g.b.timedTick()
        runCurrent()
        val claim = g.r.ofKind(Lt2.KIND_CLAIM).single()
        assertEquals(listOf(listOf("y", cmdY), listOf("hops", "2")), claim.tags)
        advanceTimeBy(4_000)
        assertEquals("waits for at", 0, g.m.sent.size)
        advanceUntilIdle()
        // a standing tag two repeaters away: along the reversed path of via, then the 0xFF6C flood 12 s later
        val (first, fallback) = g.m.sent
        assertEquals(at * 1000, first.atMs)
        assertEquals(pc.hex(), first.payload.hex())
        assertEquals("bbaa", ((first.route as Route.Direct).path.hex()))
        assertEquals(Frames.DATA_TYPE_LIBRETAG, first.dataType)
        assertEquals(Route.Flood, fallback.route)
        assertEquals(0xFF6C, fallback.dataType)
        assertEquals(at * 1000 + 12_000, fallback.atMs)
        // once only
        g.b.timedTick()
        advanceUntilIdle()
        assertEquals(2, g.m.sent.size)
    }

    @Test
    fun timedEntryNeverAnswersAReport() = runTest {
        val g = rig()
        g.b.onRelayEvent(command(g.nowS(), at = g.nowS() + 3600, via = x).toJson())
        advanceTimeBy(1000)
        g.b.hear(p, direct0)
        advanceTimeBy(10_000)
        assertEquals(0, g.m.sent.size)
        assertEquals(1, g.b.mailbox.size)
    }

    @Test
    fun timedEntryNeedsTheViaReportAndFloodsOtherwise() = runTest {
        val g = rig()
        g.b.onRelayEvent(command(g.nowS(), at = g.nowS() + 1, via = "01020304").toJson())
        advanceTimeBy(2_000)
        g.b.timedTick()
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
        assertTrue(g.b.activity.value.any { it.text.contains("01020304") })

        // a server without tx_path_data: zero-hop impossible, the entry floods
        val h = rig(caps = emptySet())
        h.b.hear(p, RxMeta(hops = 0))
        h.b.onRelayEvent(command(h.nowS(), ys = listOf(cmdY), at = h.nowS() + 1, via = x).toJson())
        advanceTimeBy(2_000)
        h.b.timedTick()
        advanceUntilIdle()
        assertEquals(Route.Flood, h.m.sent.single().route)

        // heard directly with tx_path_data: zero-hop
        val z = rig()
        z.b.hear(p, RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        z.b.onRelayEvent(command(z.nowS(), ys = listOf(cmdY), at = z.nowS() + 1, via = x).toJson())
        advanceTimeBy(2_000)
        z.b.timedTick()
        advanceUntilIdle()
        assertEquals(Route.ZeroHop, z.m.sent.single().route)
    }

    @Test
    fun lateTimedEntriesAreDropped() = runTest {
        val g = rig()
        g.b.hear(p, direct0)
        g.b.onRelayEvent(command(g.nowS(), at = g.nowS() - 61, via = x).toJson())
        assertEquals(0, g.b.mailbox.size)
        g.b.onRelayEvent(command(g.nowS(), at = g.nowS() - 30, via = x).toJson()) // the owner (a stranger: refused, report x locked)
        assertEquals(1, g.b.mailbox.size)
        // timed entries off: refused
        val h = rig(cfg = BridgeConfig(timedCommands = false))
        h.b.onRelayEvent(command(h.nowS(), at = h.nowS() + 60, via = x).toJson())
        assertEquals(0, h.b.mailbox.size)
    }

    @Test
    fun parseTimedTags() {
        val nowS = t0 / 1000
        val c = Mailbox.parse(command(nowS, at = nowS + 10, via = x), t0)!!
        assertEquals(nowS + 10, c.at)
        assertEquals(x, c.via)
        assertEquals("signed with sk_n of report via", Owner.pk(Owner.nOf(x)!!), c.pubkey)
        assertNull("at without via", Mailbox.parse(command(nowS, at = nowS + 10), t0))
        assertNull("bad via", Mailbox.parse(command(nowS, at = nowS + 10, via = "XYZ"), t0))
        assertNull(Mailbox.parse(command(nowS, at = nowS + 10, via = x), t0, timed = false))
        assertNull(Mailbox.parse(command(nowS).let { e ->
            NostrEvent.sign(testKey(50), nowS, e.kind, e.tags + listOf(listOf("at", "-5"), listOf("via", x)), e.content)
        }, t0))
    }

    @Test
    fun reportPathsKeepFewestHopsFor48h() {
        val rp = ReportPaths()
        rp.put("aa000001", 3, "010203".unhex(), 1, 0)
        rp.put("aa000001", 1, "09".unhex(), 1, 10)
        rp.put("aa000001", 2, "0708".unhex(), 1, 20)
        rp.put("aa000001", null, null, 1, 30)
        assertEquals(1, rp.get("aa000001", 40)!!.hops)
        assertEquals("09", rp.get("aa000001", 40)!!.path)
        assertNull(rp.get("aa000001", 10 + 48 * 3600_000L + 1))
        rp.put("aa000002", null, null, 1, 0)
        rp.put("aa000002", 4, "01020304".unhex(), 1, 5)
        assertEquals("unknown hops are replaced", 4, rp.get("aa000002", 6)!!.hops)
    }

    // ------------------------------------------------------------------ cancel (NIP-09)

    @Test
    fun deletionBySameKeyCancelsOtherKeyDoesNot() = runTest {
        val g = rig()
        val ev = command(g.nowS())
        g.b.onRelayEvent(ev.toJson())
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS(), key = 51).toJson()) // someone else
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS(), k = "1").toJson()) // not about 4244
        assertEquals(1, g.b.mailbox.size)
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS()).toJson())
        assertEquals(0, g.b.mailbox.size)
        advanceTimeBy(1000)
        g.b.hear(p, direct0)
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
    }

    @Test
    fun deletionBeforeTheEventIsRememberedAcrossRestart() = runTest {
        val g = rig()
        val ev = command(g.nowS())
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS()).toJson())
        g.b.hear(p, twoHops)
        advanceUntilIdle()
        val state = Json.encodeToString(BridgeState.serializer(), g.b.exportState())

        val h = rig()
        h.b.importState(Json.decodeFromString(BridgeState.serializer(), state))
        h.b.onRelayEvent(ev.toJson())
        assertEquals(0, h.b.mailbox.size)
        assertEquals("report path survives too", 2, h.b.reportPaths.get(x, h.clock())!!.hops)
    }

    @Test
    fun deletionDuringElectionStopsTheSend() = runTest {
        val g = rig()
        val ev = command(g.nowS())
        g.b.onRelayEvent(ev.toJson())
        advanceTimeBy(1000)
        g.b.hear(p, twoHops) // a path send: taken, claim published, waiting 1.5 s (zero-hop has no claim round)
        runCurrent()
        assertEquals(1, g.r.ofKind(Lt2.KIND_CLAIM).size)
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS()).toJson())
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
    }

    @Test
    fun deletionOfTimedEntryBetweenClaimAndAt() = runTest {
        val g = rig()
        g.b.hear(p, direct0)
        val ev = command(g.nowS(), ys = listOf(cmdY), at = g.nowS() + 10, via = x)
        g.b.onRelayEvent(ev.toJson())
        advanceTimeBy(6_000)
        g.b.timedTick()
        runCurrent()
        assertEquals(1, g.r.ofKind(Lt2.KIND_CLAIM).size)
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS()).toJson())
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
    }

    @Test
    fun deletionAfterSendingIsOnlyLogged() = runTest {
        val g = rig()
        val ev = command(g.nowS())
        g.b.onRelayEvent(ev.toJson())
        advanceTimeBy(1000)
        g.b.hear(p, direct0)
        advanceUntilIdle()
        assertEquals(1, g.m.sent.size)
        g.b.onRelayEvent(deletion(listOf(ev.id), g.nowS()).toJson())
        assertTrue(g.b.activity.value.first().text.contains("vyslaný"))
        // the same event coming again from another relay is not re-added
        g.b.onRelayEvent(ev.toJson())
        assertEquals(0, g.b.mailbox.size)
    }

    @Test
    fun mailboxCancelResults() {
        val mb = Mailbox()
        fun cmd(id: String, pk: String) = Mailbox.Command(id, listOf("0000000$id"), false, pc, 0, 3600_000, 1, pubkey = pk)
        assertTrue(mb.add(cmd("1", "aa"), 0))
        assertEquals(Mailbox.Cancel.OTHER_KEY, mb.cancel("1", "bb", 1))
        assertTrue(mb.pending("1"))
        assertEquals(Mailbox.Cancel.CANCELLED, mb.cancel("1", "aa", 1))
        assertFalse(mb.pending("1"))
        assertEquals(Mailbox.Cancel.UNKNOWN, mb.cancel("2", "aa", 1))
        assertFalse("cancelled before arrival", mb.add(cmd("2", "aa"), 2))
        assertTrue("another key's event with that id is not blocked", mb.add(cmd("2", "cc"), 2))
        val c3 = cmd("3", "aa")
        assertTrue(mb.add(c3, 3))
        mb.take(c3, 3)
        mb.markSent(c3, 4)
        assertEquals(Mailbox.Cancel.SENT, mb.cancel("3", "aa", 5))
        assertNotNull(mb.export().sent["3"])
    }
}

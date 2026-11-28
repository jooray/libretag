package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.RadioHealth
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Market
import app.nostrmesh.libretag.QuietMesh
import app.nostrmesh.mesh.meta
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.unhex
import io.github.jooray.meshcore.localapi.Capabilities
import io.github.jooray.meshcore.localapi.MeshEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * Quiet mesh in the Android bridge (PROTOCOL.md 3.3, 3.4, 4.3; docs/design/quiet-mesh.md, "Android bridge"):
 * the shared vectors (docs/vectors/quiet-mesh-v1.json) and the contract's bridge tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuietMeshTest {
    private val qm = Json.parseToJsonElement(javaClass.classLoader!!.getResource("quiet-mesh-v1.json")!!.readText()).jsonObject
    private val lt2 = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lt2.json")!!.readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.strs(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private val pc = lt2["commands"]!!.jsonArray[0].jsonObject.s("p").unhex()!!
    private fun pcN(i: Int) = pc.copyOf().also { it[28] = i.toByte(); it[27] = (i shr 8).toByte() }

    // ------------------------------------------------------------------ vectors

    @Test
    fun planVectors() {
        for (v in qm["plan"]!!.jsonArray.map { it.jsonObject }) {
            val h = v["heard"]!!.takeIf { it !is JsonNull }?.jsonObject
            val heard = h?.let { QuietMesh.Heard(it.s("route"), it.strs("path")) }
            val a = v["args"]!!.jsonObject
            val got = QuietMesh.plan(
                v.s("kind"), heard, v.s("route"), viaPath = a["via_path"]?.jsonArray?.map { it.jsonPrimitive.content },
                forms = a["forms"]?.jsonPrimitive?.content ?: "near", fb = a["fb"]?.jsonPrimitive?.boolean ?: true,
                maxHops = a["max_hops"]?.jsonPrimitive?.int ?: QuietMesh.PATH_MAX_HOPS,
                canZeroHop = a["can_zero_hop"]?.jsonPrimitive?.boolean ?: true,
                canPath = a["can_path"]?.jsonPrimitive?.boolean ?: true, blind = a["blind"]?.jsonPrimitive?.boolean ?: false,
            )
            val want = v["plan"]!!.jsonObject
            val msg = v.toString()
            assertEquals(msg, want.s("send"), got.send)
            assertEquals(msg, want.strs("path"), got.path)
            assertEquals(msg, want["fallback"]!!.jsonPrimitive.boolean, got.fallback)
            assertEquals(msg, want["form"]?.jsonPrimitive?.content, got.form)
            assertEquals(msg, want["scope"]?.jsonPrimitive?.content, got.scope)
            assertEquals(msg, want["tag_path"]?.jsonArray?.map { it.jsonPrimitive.content }, got.tagPath)
        }
    }

    @Test
    fun routedAckVectors() {
        for (v in qm["acks_routed"]!!.jsonObject["valid"]!!.jsonArray.map { it.jsonObject }) {
            val hs = v["hash_size"]!!.jsonPrimitive.int
            val dg = QuietMesh.ackRouted(v.s("pa").unhex()!!, hs, v.strs("path").map { it.unhex()!! })
            assertEquals(v.s("datagram"), dg.hex())
            assertEquals(v.s("channel_payload"), QuietMesh.channelWrap(dg, Frames.DATA_TYPE_LIBRETAG).hex())
            assertTrue(dg.size in QuietMesh.ROUTED_ACK_LENS)
            // the packet along the reversed path, and its last hop with the path used up
            val pkt = v.s("packet").unhex()!!
            assertEquals(QuietMesh.pathLenByte(hs, v.strs("path").size), pkt[1].toInt() and 0xFF)
            assertEquals(QuietMesh.pathBytes(v.strs("path").reversed()).hex(), pkt.copyOfRange(2, 2 + hs * v.strs("path").size).hex())
        }
        for (v in qm["acks"]!!.jsonObject["valid"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(v.s("channel_payload"), QuietMesh.channelWrap(v.s("pa").unhex()!!, Frames.DATA_TYPE_LIBRETAG).hex())
        }
        // the ACK entry's 26 B content is near || routed: the second half is the routed vector's Pa
        val both = qm["ack_entries"]!!.jsonArray.map { it.jsonObject }.single { it.s("form") == "both" }
        val raw = Base64.getDecoder().decode(both.s("content"))
        assertEquals(qm["acks_routed"]!!.jsonObject["valid"]!!.jsonArray[0].jsonObject.s("pa"), raw.copyOfRange(13, 26).hex())
        for (e in qm["ack_entries"]!!.jsonArray.map { it.jsonObject }) {
            val n = Base64.getDecoder().decode(e.s("content")).size
            val route = if (e.s("form") == "near") "near" else "direct"
            assertEquals(e.s("form"), QuietMesh.ackForms(n, route))
        }
        assertNull(QuietMesh.ackForms(26, "near"))
        assertNull(QuietMesh.ackForms(29, "direct"))
    }

    @Test
    fun retryDataTypeAndTransportVectors() {
        for (v in qm["retry"]!!.jsonArray.map { it.jsonObject }) {
            val p = v.s("pc").unhex()!!
            assertEquals(v.s("payload_ff4c"), QuietMesh.channelWrap(p, Frames.DATA_TYPE_LIBRETAG).hex())
            assertEquals(v.s("payload_ff6c"), QuietMesh.channelWrap(p, QuietMesh.DATA_TYPE_RETRY).hex())
        }
        for (v in qm["transport"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(v.s("key"), QuietMesh.scopeKey(v.s("scope")).hex())
            assertEquals(v["code"]!!.jsonPrimitive.int, QuietMesh.transportCode(v.s("scope"), v["payload_type"]!!.jsonPrimitive.int, v.s("payload").unhex()!!))
        }
        // a scoped flood copy: the bridge rebuilds its payload and finds the scope from code[0]
        var scoped = 0
        for (c in qm["copies"]!!.jsonArray.flatMap { it.jsonObject["copies"]!!.jsonArray }.map { it.jsonObject }) {
            val pkt = c.s("packet").unhex()!!
            val p = c.s("p").unhex()!!
            val scope = c["scope"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val route = pkt[0].toInt() and 3
            if (route == 0) { // TRANSPORT_FLOOD: header, codes, path_len, payload
                val code = (pkt[1].toInt() and 0xFF) or ((pkt[2].toInt() and 0xFF) shl 8)
                assertEquals(QuietMesh.channelWrap(p, Frames.DATA_TYPE_LIBRETAG).hex(), pkt.copyOfRange(6, pkt.size).hex())
                assertEquals(scope, QuietMesh.replyScope(null, code, Frames.DATA_TYPE_LIBRETAG, p, listOf("sk-test", "sk")))
                assertNull(QuietMesh.replyScope(null, code, Frames.DATA_TYPE_LIBRETAG, p, listOf("cz")))
                scoped++
            }
        }
        assertTrue(scoped >= 2)
        assertEquals("sk", QuietMesh.replyScope("sk", null, 0, ByteArray(0), listOf("sk-test", "sk")))
    }

    // ------------------------------------------------------------------ bridge

    private val t0 = 1_763_020_900_000L
    private val caps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH, Caps.RX_META)
    private val flood0 = RxMeta(routeType = "flood", hops = 0, path = ByteArray(0), pathHashSize = 1)
    private val direct = RxMeta(routeType = "direct", path = ByteArray(0), pathHashSize = 1)
    private fun floodVia(vararg h: String) = RxMeta(routeType = "tflood", hops = h.size, path = QuietMesh.pathBytes(h.toList()), pathHashSize = 2)
    private val hops1 = floodVia("a5e1")
    private val hops2 = floodVia("a5e1", "b7d2")
    private val hops3 = floodVia("a5e1", "b7d2", "c3f4")
    private val me = testKey(500)
    private val mePub = testPub(500).hex()

    private class Rig(val b: Bridge, val m: FakeMesh, val r: FakeRelays, val clock: () -> Long)

    private fun TestScope.rig(cfg: BridgeConfig = BridgeConfig()): Rig {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        val clock = { t0 + testScheduler.currentTime }
        m.clock = clock
        return Rig(Bridge(this, m, r, cfg.legacyTiming(), emptyList(), clock = clock, random = Random(7), newKey = keySource(),
            bridgeKey = me), m, r, clock)
    }

    private fun Rig.nowS() = clock() / 1000
    private fun Rig.counter(k: String) = b.status()["counters"]!!.jsonObject[k]?.jsonPrimitive?.longOrNull ?: 0L
    private fun Rig.notes(k: String) = r.ofKind(Lt2.KIND_CLAIM).filter { e -> e.tags.any { it[0] == k } }
    private fun Rig.first() = m.sent.filter { it.dataType == Frames.DATA_TYPE_LIBRETAG }
    private fun Rig.fallbacks() = m.sent.filter { it.dataType == QuietMesh.DATA_TYPE_RETRY }

    private suspend fun Bridge.hear(p: ByteArray, meta: RxMeta) {
        ownerAnchor(p, t0 / 1000)
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, meta)
    }

    private val near = Base64.getDecoder().decode(qm["ack_entries"]!!.jsonArray[0].jsonObject.s("content"))
    private val both = Base64.getDecoder().decode(qm["ack_entries"]!!.jsonArray[1].jsonObject.s("content"))
    private val routed = Base64.getDecoder().decode(qm["ack_entries"]!!.jsonArray[2].jsonObject.s("content"))

    /** An ACK entry signed with sk of [signer] (the report it ACKs, or for a timed one the anchor). */
    private fun ackEntry(
        createdS: Long, y: String, content: ByteArray, route: String, via: String? = null, at: Long? = null,
        signer: String = via?.takeIf { at != null } ?: y, p: String? = null,
    ) = NostrEvent.sign(Synth.skOf(signer)!!, createdS, Lt2.KIND_COMMAND,
        listOf(listOf("v", "2"), listOf("a", "1"), listOf("y", y), listOf("route", route),
            listOf("expiration", "${(at ?: createdS) + 600}")) +
            listOfNotNull(via?.let { listOf("via", it) }, at?.let { listOf("at", "$it") }, p?.let { listOf("p", it) }),
        Base64.getEncoder().encodeToString(content))

    private val GRP = "00112233445566ff"

    private fun cmdEntry(
        createdS: Long, ys: List<String>, route: String, payload: ByteArray = pc, g: String? = null, at: Long? = null,
        via: String? = null, fb: Boolean = true,
    ) = NostrEvent.sign(Synth.skOf(via?.takeIf { at != null } ?: ys.first())!!, createdS, Lt2.KIND_COMMAND,
        listOf(listOf("v", "2"), listOf("route", route), listOf("expiration", "${createdS + 6 * 3600}")) +
            ys.map { listOf("y", it) } + listOfNotNull(g?.let { listOf("g", it) }, at?.let { listOf("at", "$it") },
                via?.let { listOf("via", it) }, if (fb) null else listOf("fb", "0")),
        Base64.getEncoder().encodeToString(payload))

    private fun TestScope.startAt(g: Rig, atS: Long) {
        advanceTimeBy(maxOf(atS * 1000 - 5_000 - g.clock(), 0))
        g.b.timedTick()
        runCurrent()
    }

    @Test
    fun nearAckEntryGoesZeroHopAfterJitterWithAnAckSentNote() = runTest {
        for (heard in listOf(flood0, direct)) {
            val g = rig()
            val p = Synth.report(800)
            val y = Lt2.tag(p)
            g.b.hear(p, heard)
            runCurrent()
            g.b.onRelayEvent(ackEntry(g.nowS(), y, near, "near").toJson()) // the app opened the report: a late entry
            val t1 = g.clock()
            advanceUntilIdle()
            val s = g.first().single()
            assertEquals(Route.ZeroHop, s.route)
            assertEquals(near.hex(), s.payload.hex())
            assertTrue("jitter 100..600 ms", s.atMs - t1 in 100..600)
            val note = g.notes("ack").single()
            assertEquals(listOf("y", y), note.tags[0])
            assertEquals(listOf("ack", "1"), note.tags[1])
            assertEquals(mePub, note.pubkey)
            assertEquals(0, g.fallbacks().size)
            assertEquals("no claim for a near ACK", 0, g.r.ofKind(Lt2.KIND_CLAIM).count { e -> e.tags.any { it[0] == "hops" } })
        }
    }

    @Test
    fun ackEntriesRefusedOverThreeHopsFromUnlockedKeysAndNeverFlooded() = runTest {
        // over 3 hops: neither form
        for (form in listOf(near to "near", both to "direct")) {
            val g = rig()
            val p = Synth.report(801)
            g.b.hear(p, hops3)
            runCurrent()
            g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(p), form.first, form.second).toJson())
            advanceUntilIdle()
            assertEquals("$form: never a flood", 0, g.m.sent.size)
        }
        // a stranger's key never locked report p
        val g = rig()
        val p = Synth.report(802)
        g.b.hear(p, flood0)
        runCurrent()
        val stranger = NostrEvent.sign(testKey(77), g.nowS(), Lt2.KIND_COMMAND, listOf(listOf("v", "2"), listOf("a", "1"),
            listOf("y", Lt2.tag(p)), listOf("route", "near"), listOf("expiration", "${g.nowS() + 600}")),
            Base64.getEncoder().encodeToString(near))
        g.b.onRelayEvent(stranger.toJson())
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
        // a near-only entry (the tag moved) for a copy heard over a path: no routed form
        val h = rig()
        val p2 = Synth.report(803)
        h.b.hear(p2, hops1)
        runCurrent()
        h.b.onRelayEvent(ackEntry(h.nowS(), Lt2.tag(p2), near, "near").toJson())
        advanceUntilIdle()
        assertEquals(0, h.m.sent.size)
    }

    @Test
    fun anOpenAnchorLocksAtABridgeThatServesNoOpenEntries() = runTest {
        // PROTOCOL.md 4.1, 4.3: the anchor names no bridge, yet the ACK entry needs the lock it makes
        val g = rig(BridgeConfig(acceptOpen = false))
        val p = Synth.report(828)
        g.b.onRelayEvent(NostrEvent.sign(Synth.skOf(Lt2.tag(p))!!, g.nowS() - 70, Lt2.KIND_COMMAND, listOf(listOf("v", "2"),
            listOf("y", Lt2.tag(p)), listOf("expiration", "${g.nowS() + 3600}")), "").toJson())
        g.b.onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, flood0)
        runCurrent()
        g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(p), near, "near").toJson())
        advanceUntilIdle()
        assertEquals(1, g.first().size)
        // an open command is still refused
        val p2 = Synth.report(829)
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(Lt2.tag(p2)), "near").toJson())
        advanceTimeBy(1000)
        g.b.onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p2, flood0)
        advanceUntilIdle()
        assertEquals(1, g.first().size)
    }

    @Test
    fun anAckSkippedAsHeardOnAirCostsNoBudget() = runTest {
        val g = rig(BridgeConfig(acksPerHour = 1))
        for ((i, n) in listOf(830, 831).withIndex()) {
            val p = Synth.report(n)
            val content = near.copyOf().also { it[0] = n.toByte() }
            g.b.hear(p, flood0)
            runCurrent()
            g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(p), content, "near").toJson())
            runCurrent()
            if (i == 0) g.b.onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, content, direct) // the owner's node was faster
            advanceUntilIdle()
        }
        assertEquals("the first was heard on air, the second fits the budget of one",
            listOf(near.copyOf().also { it[0] = 831.toByte() }.hex()), g.first().map { it.payload.hex() })
    }

    @Test
    fun ackEntryPassesABridgeThatServesNoOpenEntries() = runTest {
        val g = rig(BridgeConfig(acceptOpen = false))
        val p = Synth.report(804)
        // its anchor names this bridge (a closed bridge stores only those); the ACK entry itself names none
        g.b.onRelayEvent(NostrEvent.sign(Synth.skOf(Lt2.tag(p))!!, g.nowS() - 10, Lt2.KIND_COMMAND, listOf(listOf("v", "2"),
            listOf("y", Lt2.tag(p)), listOf("p", mePub), listOf("expiration", "${g.nowS() + 3600}")), "").toJson())
        g.b.onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, flood0)
        runCurrent()
        g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(p), near, "near").toJson())
        advanceUntilIdle()
        assertEquals(1, g.first().size)
    }

    @Test
    fun routedAckForOneAndTwoHopsNotThree() = runTest {
        for ((heard, want) in listOf(hops1 to listOf("a5e1"), hops2 to listOf("b7d2", "a5e1"), hops3 to null)) {
            val g = rig()
            val p = Synth.report(805)
            val y = Lt2.tag(p)
            g.b.hear(p, heard)
            runCurrent()
            g.b.onRelayEvent(ackEntry(g.nowS(), y, both, "direct").toJson())
            runCurrent()
            if (want == null) {
                advanceUntilIdle()
                assertEquals(0, g.m.sent.size)
                continue
            }
            // the first routed ACK of a flood: a 2 s claim round
            val claim = g.r.ofKind(Lt2.KIND_CLAIM).single { e -> e.tags.any { it[0] == "hops" } }
            assertEquals(listOf("hops", "${want.size}"), claim.tags[1])
            advanceTimeBy(1_900)
            assertEquals(0, g.m.sent.size)
            advanceUntilIdle()
            val s = g.first().single()
            val tagPath = want.reversed()
            assertEquals(QuietMesh.pathBytes(want).hex(), (s.route as Route.Direct).path.hex())
            assertEquals(QuietMesh.ackRouted(both.copyOfRange(13, 26), 2, tagPath.map { it.unhex()!! }).hex(), s.payload.hex())
            assertEquals(listOf("ack", "2"), g.notes("ack").single().tags[1])
            assertTrue("the path record: the flood copy itself", listOf("via", y) in g.notes("ack").single().tags)
        }
    }

    @Test
    fun routedAckSkippedOnAnotherBridgesAckSentNote() = runTest {
        val g = rig()
        val p = Synth.report(806)
        val y = Lt2.tag(p)
        g.b.hear(p, hops1)
        runCurrent()
        g.b.onRelayEvent(ackEntry(g.nowS(), y, both, "direct").toJson())
        runCurrent()
        val other = NostrEvent.sign(testKey(600), g.nowS(), Lt2.KIND_CLAIM, listOf(listOf("y", y), listOf("ack", "1"), listOf("e", "00".repeat(32))), "")
        g.b.onRelayEvent(other.toJson())
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
    }

    @Test
    fun copy11AckGoesAlongThePathStoredForVia() = runTest {
        val g = rig()
        val v = Synth.report(807) // a flood copy over two repeaters: the path record
        val x11 = Synth.report(808) // later: copy 11, heard DIRECT with an empty path
        g.b.hear(v, hops2)
        runCurrent()
        g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(v), both, "direct").toJson())
        advanceUntilIdle()
        assertEquals(1, g.first().size)
        advanceTimeBy(3 * 3600_000L)
        g.b.hear(x11, direct)
        runCurrent()
        g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(x11), routed, "direct", via = Lt2.tag(v), p = mePub).toJson())
        advanceUntilIdle()
        val s = g.first().last()
        assertEquals(2, g.first().size)
        assertEquals("b7d2a5e1", (s.route as Route.Direct).path.hex())
        assertEquals(QuietMesh.ackRouted(routed, 2, listOf("a5e1".unhex()!!, "b7d2".unhex()!!)).hex(), s.payload.hex())
        assertEquals("no claim for an entry along a stored path", 1, g.r.ofKind(Lt2.KIND_CLAIM).count { e -> e.tags.any { it[0] == "hops" } })
        assertTrue("the note names the record whose path it used", listOf("via", Lt2.tag(v)) in g.notes("ack").last().tags)
        // the path is kept under the new x
        assertEquals("a5e1b7d2", g.b.reportPaths.get(Lt2.tag(x11), g.clock())!!.path)
        // a DIRECT arrival with a near entry that names no path: zero-hop; with route direct and no path: nothing
        val x9 = Synth.report(809)
        g.b.hear(x9, direct)
        runCurrent()
        g.b.onRelayEvent(ackEntry(g.nowS(), Lt2.tag(x9), routed, "direct").toJson())
        advanceUntilIdle()
        assertEquals(2, g.first().size)
    }

    @Test
    fun zeroHopWithBothHasAFallbackWithOtherBytes() = runTest {
        val g = rig()
        val p = Synth.report(810)
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(Lt2.tag(p)), "both", g = GRP).toJson())
        advanceTimeBy(1000)
        g.b.hear(p, flood0)
        advanceUntilIdle()
        val (a, b) = g.m.sent
        assertEquals(Route.ZeroHop, a.route)
        assertEquals(Frames.DATA_TYPE_LIBRETAG, a.dataType)
        assertEquals(Route.Flood, b.route)
        assertEquals("fallback: data type 0xFF6C, other bytes on air", QuietMesh.DATA_TYPE_RETRY, b.dataType)
        assertEquals(pc.hex(), b.payload.hex())
        assertEquals(12_000L, b.atMs - a.atMs)
        // without g nothing could tell the answer: no fallback (PROTOCOL.md 4.3)
        val k = rig()
        val p3 = Synth.report(812)
        k.b.onRelayEvent(cmdEntry(k.nowS(), listOf(Lt2.tag(p3)), "both").toJson())
        advanceTimeBy(1000)
        k.b.hear(p3, flood0)
        advanceUntilIdle()
        assertEquals(listOf(Route.ZeroHop), k.m.sent.map { it.route })
        // route flood, fb 0: no fallback after a path send
        val h = rig()
        val p2 = Synth.report(811)
        h.b.onRelayEvent(cmdEntry(h.nowS(), listOf(Lt2.tag(p2)), "flood", fb = false).toJson())
        advanceTimeBy(1000)
        h.b.hear(p2, hops2)
        advanceUntilIdle()
        assertEquals(1, h.m.sent.size)
        assertTrue(h.m.sent.single().route is Route.Direct)
    }

    @Test
    fun anAnswerInTimeCancelsTheFallbackAndClosesTheGroup() = runTest {
        // the answer comes back along the path: DIRECT with an empty path, any hop count
        for (answer in listOf(direct, hops3)) {
            val g = rig()
            val pn = Synth.report(812); val pn1 = Synth.report(813)
            val grp = "00112233aabbccdd"
            val n = g.nowS()
            g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(pn)), "flood", pcN(1), grp).toJson())
            g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(pn1)), "flood", pcN(2), grp).toJson())
            advanceTimeBy(1000)
            g.b.hear(pn, hops2)
            advanceTimeBy(1_500 + 5_000) // claim round, path send, then the answer 5 s later
            assertEquals(1, g.first().size)
            g.b.hear(pn1, answer)
            advanceUntilIdle()
            assertEquals("$answer: no fallback", 0, g.fallbacks().size)
            assertEquals(1L, g.counter("lt_fallback_cancelled"))
            // R34: report n + 1 (a y of another untimed entry than the one sent for n) closes the group
            assertEquals(listOf(listOf("g", grp), listOf("done", Lt2.tag(pn1))), g.notes("done").single().tags)
        }
    }

    @Test
    fun theNextReportClosesTheGroupButASiblingCopyOnlyAfter120s() = runTest {
        val grp = "0011223344556677"
        // report 5 s after via with a y of the group's next counter: the timed entries are dropped
        val g = rig()
        val v = Synth.report(814); val next = Synth.report(815)
        g.b.hear(v, flood0)
        runCurrent()
        val n = g.nowS()
        g.b.onRelayEvent(cmdEntry(n, listOf("c0de0101"), "flood", pcN(3), grp, at = n + 900, via = Lt2.tag(v)).toJson())
        g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(next)), "flood", pcN(4), grp).toJson())
        advanceTimeBy(5_000)
        g.b.hear(next, hops3)
        advanceUntilIdle()
        assertEquals(1, g.notes("done").size)
        startAt(g, n + 900)
        advanceUntilIdle()
        assertEquals("timed entry dropped", 0, g.m.sent.count { it.payload.contentEquals(pcN(3)) })
        // a sibling copy of via (a y of the same untimed entry) within 120 s is not an answer; after 120 s it is
        for ((gapMs, closes) in listOf(12_000L to false, 130_000L to true)) {
            val h = rig()
            val c9 = Synth.report(816); val c10 = Synth.report(817)
            val m = h.nowS()
            h.b.onRelayEvent(cmdEntry(m, listOf(Lt2.tag(c9), Lt2.tag(c10)), "flood", pcN(5), grp).toJson())
            h.b.onRelayEvent(cmdEntry(m, listOf("c0de0102"), "flood", pcN(6), grp, at = m + 900, via = Lt2.tag(c9)).toJson())
            advanceTimeBy(1000)
            h.b.hear(c9, flood0)
            advanceTimeBy(gapMs)
            h.b.hear(c10, hops1)
            runCurrent()
            assertEquals("gap $gapMs", if (closes) 1 else 0, h.notes("done").size)
            advanceUntilIdle()
        }
    }

    @Test
    fun theReportAnUntimedEntryWaitsForDoesNotCloseTheGroup() = runTest {
        // R34: report n answers nothing yet (no send of the group before it, no timed entry): the group stays
        // open, so the timed entry the app publishes on n's grid afterwards is taken; report n + 1 closes it
        val grp = "0011223344556688"
        val g = rig()
        val pn = Synth.report(818); val pn1 = Synth.report(819)
        val n = g.nowS()
        g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(pn)), "flood", pcN(7), grp).toJson())
        g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(pn1)), "flood", pcN(8), grp).toJson())
        advanceTimeBy(1000)
        g.b.hear(pn, flood0)
        runCurrent()
        assertEquals("report n answers nothing yet", 0, g.notes("done").size)
        advanceTimeBy(5_000)
        val m = g.nowS()
        g.b.onRelayEvent(cmdEntry(m, listOf("c0de0103"), "flood", pcN(9), grp, at = m + 900, via = Lt2.tag(pn)).toJson())
        assertEquals("the timed entry was taken", (m + 900) * 1000, g.b.nextDueMs())
        advanceTimeBy(30_000)
        g.b.hear(pn1, flood0)
        runCurrent()
        assertEquals(listOf(listOf("g", grp), listOf("done", Lt2.tag(pn1))), g.notes("done").single().tags)
        assertNull("its timed entry dropped", g.b.nextDueMs())
        advanceUntilIdle()
    }

    @Test
    fun aTimedEntryOnAnOlderGridIsDroppedAtItsSlotAfterTheNextReport() = runTest {
        // reports n and n + 1 heard over a repeater, their near-only plain entries not sendable from here, so
        // nothing to answer; then a timed entry of the group on n's grid arrives: at its slot n + 1 (another
        // untimed entry, heard after the via) closes the group, nothing goes out
        val grp = "00112233445566aa"
        val g = rig()
        val pn = Synth.report(824); val pn1 = Synth.report(825)
        val n = g.nowS()
        g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(pn)), "near", pcN(14), grp).toJson())
        g.b.onRelayEvent(cmdEntry(n, listOf(Lt2.tag(pn1)), "near", pcN(15), grp).toJson())
        advanceTimeBy(1000)
        g.b.hear(pn, hops1)
        advanceTimeBy(300_000)
        g.b.hear(pn1, hops1)
        advanceTimeBy(5_000)
        assertEquals(0, g.first().size)
        assertEquals(0, g.notes("done").size)
        val m = g.nowS()
        g.b.onRelayEvent(cmdEntry(m, listOf("c0de0105"), "flood", pcN(16), grp, at = m + 900, via = Lt2.tag(pn)).toJson())
        assertEquals((m + 900) * 1000, g.b.nextDueMs())
        startAt(g, m + 900)
        advanceUntilIdle()
        assertEquals(0, g.m.sent.count { it.payload.contentEquals(pcN(16)) })
        assertEquals(listOf(listOf("g", grp), listOf("done", Lt2.tag(pn1))), g.notes("done").single().tags)
    }

    @Test
    fun theAckGoesFirstAndTheCommandForTheSameReport300msAfterIt() = runTest {
        // a routed ACK (2 s claim round) and a command along the path (1.5 s claim round) for one report
        val g = rig()
        val p = Synth.report(820)
        val y = Lt2.tag(p)
        g.b.onRelayEvent(ackEntry(g.nowS(), y, both, "direct").toJson())
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(y), "flood", pcN(10)).toJson())
        advanceTimeBy(1000)
        g.b.hear(p, hops1)
        advanceUntilIdle()
        val (ack, cmd) = g.first()
        assertTrue(ack.payload.size in QuietMesh.ROUTED_ACK_LENS)
        assertEquals(pcN(10).hex(), cmd.payload.hex())
        assertEquals(QuietMesh.ACK_BEFORE_CMD_MS, cmd.atMs - ack.atMs)
    }

    @Test
    fun theAckAtASlotGoesBeforeTheCommandOfTheSameSlot() = runTest {
        val g = rig()
        val v = Synth.report(821)
        g.b.hear(v, flood0)
        runCurrent()
        val n = g.nowS()
        val at = n + 900
        g.b.onRelayEvent(cmdEntry(n, listOf("c0de0104"), "near", pcN(11), at = at, via = Lt2.tag(v)).toJson())
        g.b.onRelayEvent(ackEntry(n, Lt2.tag(v), near, "near", via = Lt2.tag(v), at = at).toJson())
        startAt(g, at)
        advanceUntilIdle()
        val (ack, cmd) = g.first()
        assertEquals(near.hex(), ack.payload.hex())
        assertEquals(pcN(11).hex(), cmd.payload.hex())
        assertEquals(QuietMesh.ACK_BEFORE_CMD_MS, cmd.atMs - ack.atMs)
    }

    @Test
    fun theAckBudgetHoldsForTimedAcksWithAMissNote() = runTest {
        val g = rig(BridgeConfig(acksPerHour = 0))
        val v = Synth.report(826)
        g.b.hear(v, flood0)
        runCurrent()
        val n = g.nowS()
        g.b.onRelayEvent(ackEntry(n, Lt2.tag(v), near, "near", via = Lt2.tag(v), at = n + 900).toJson())
        startAt(g, n + 900)
        advanceUntilIdle()
        assertEquals(0, g.first().size)
        assertEquals(listOf("miss", "blocked"), g.notes("miss").single().tags.last())
    }

    @Test
    fun anUntimedZeroHopCommandRunsTheClaimRound() = runTest {
        val g = rig()
        val p = Synth.report(822)
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(Lt2.tag(p)), "near", pcN(12)).toJson())
        advanceTimeBy(1000)
        g.b.hear(p, flood0)
        val t1 = g.clock()
        runCurrent()
        assertEquals(1, g.r.ofKind(Lt2.KIND_CLAIM).count { e -> e.tags.any { it[0] == "hops" } })
        advanceUntilIdle()
        val s = g.first().single()
        assertEquals(Route.ZeroHop, s.route)
        assertEquals(1_500L, s.atMs - t1)
    }

    @Test
    fun noFallbackWhenAnotherBridgesFallbackWasHeard() = runTest {
        val g = rig()
        val p = Synth.report(823)
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(Lt2.tag(p)), "flood", pcN(13), "0011223344556699").toJson())
        advanceTimeBy(1000)
        g.b.hear(p, hops1)
        advanceTimeBy(1_500 + 6_000)
        assertEquals(1, g.first().size)
        // another bridge's 0xFF6C flood of the same Pc reaches this radio before our 12 s are up
        g.b.onDatagram(Frames.CHANNEL_LIBRETAG, QuietMesh.DATA_TYPE_RETRY, pcN(13), hops1)
        advanceUntilIdle()
        assertEquals(0, g.fallbacks().size)
        assertEquals(1L, g.counter("lt_fallback_dup"))
    }

    @Test
    fun aPathRecordLivesFromItsLastUse() = runTest {
        val g = rig()
        val v = Synth.report(818)
        g.b.hear(v, hops2) // day 1
        advanceUntilIdle()
        // day 2: a timed routed ACK at a slot along v's path renews the record and its lock
        advanceTimeBy(24 * 3600_000L)
        val at2 = g.nowS() + 30
        g.b.onRelayEvent(ackEntry(g.nowS(), "a0a0a0a0", routed, "direct", via = Lt2.tag(v), at = at2).toJson())
        startAt(g, at2)
        advanceUntilIdle()
        assertEquals(1, g.first().size)
        assertEquals("b7d2a5e1", (g.first().single().route as Route.Direct).path.hex())
        // day 3, more than 48 h after the hearing: an entry with the day-1 via still finds the path
        advanceTimeBy(24 * 3600_000L)
        assertTrue(g.clock() - t0 > 48 * 3600_000L)
        val at3 = g.nowS() + 30
        assertNotNull(g.b.reportPaths.get(Lt2.tag(v), g.clock()))
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf("c0de0103"), "direct", pcN(7), at = at3, via = Lt2.tag(v)).toJson())
        startAt(g, at3)
        advanceUntilIdle()
        assertEquals(2, g.first().size)
        assertEquals("b7d2a5e1", (g.first().last().route as Route.Direct).path.hex())
        assertEquals(pcN(7).hex(), g.first().last().payload.hex())
    }

    @Test
    fun aRadioErrorAtAtIsRetriedAndSentOnce() = runTest {
        val g = rig()
        val v = Synth.report(819)
        g.b.hear(v, flood0)
        advanceUntilIdle()
        val at = g.nowS() + 60
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf("c0de0104"), "direct", pcN(8), at = at, via = Lt2.tag(v)).toJson())
        g.m.fail = "not_connected"
        launch { delay(at * 1000 + 4_000 - g.clock()); g.m.fail = null }
        startAt(g, at)
        advanceUntilIdle()
        val s = g.m.sent.single()
        assertTrue("sent 4 s after at, at a 2 s retry", s.atMs in at * 1000 + 4_000..at * 1000 + 6_000)
        assertEquals(0, g.notes("miss").size)
        // the radio stays down: retried until at + 20 s, then a miss note
        val h = rig()
        h.b.hear(v, flood0)
        advanceUntilIdle()
        val at2 = h.nowS() + 60
        val ev = cmdEntry(h.nowS(), listOf("c0de0105"), "direct", pcN(9), g = "0123012301230123", at = at2, via = Lt2.tag(v))
        h.b.onRelayEvent(ev.toJson())
        h.m.fail = "not_connected"
        startAt(h, at2)
        advanceUntilIdle()
        assertEquals(0, h.m.sent.size)
        val miss = h.notes("miss").single()
        assertEquals(listOf(listOf("e", ev.id), listOf("g", "0123012301230123"), listOf("at", "$at2"), listOf("miss", "radio")), miss.tags)
        assertFalse("not_connected: the radio is down", h.b.radio.up)
    }

    @Test
    fun aDeadRelayDoesNotDelayATimedEntry() = runTest {
        val g = rig()
        val v = Synth.report(820)
        g.b.hear(v, flood0)
        advanceUntilIdle()
        g.r.hangMs = 155_000 // nos.lol from here: the pool retries at 0, 5, 30, 120 s
        val at = g.nowS() + 30
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf("c0de0106"), "flood", pcN(10), at = at, via = Lt2.tag(v)).toJson())
        startAt(g, at)
        advanceTimeBy(at * 1000 + 1_000 - g.clock())
        val s = g.m.sent.single()
        assertTrue("left within 1 s of at", s.atMs in at * 1000..at * 1000 + 1_000)
        // untimed: a path send right after the report, not after the claim's publish
        val p = Synth.report(821)
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(Lt2.tag(p)), "flood", pcN(11)).toJson())
        advanceTimeBy(1000)
        val t = g.clock()
        g.b.hear(p, hops1)
        advanceTimeBy(2_000)
        assertTrue(g.m.sent.last().payload.contentEquals(pcN(11)))
        assertTrue(g.m.sent.last().atMs - t <= 1_600)
    }

    @Test
    fun aReplayedFrameIsPublishedApproximateAndLocksNothing() = runTest {
        val g = rig()
        val p = Synth.report(822)
        g.b.onRelayEvent(cmdEntry(g.nowS(), listOf(Lt2.tag(p)), "flood").toJson())
        advanceTimeBy(20 * 60_000L)
        val heardAt = g.clock() - 20 * 60_000L
        val drainedS = g.nowS()
        g.b.hear(p, flood0.copy(receivedAtMs = heardAt))
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
        assertEquals(0L, g.counter("lt_lock"))
        val ev = g.r.ofKind(Lt2.KIND_REPORT).single()
        assertEquals("created_at: when the bridge drained it", drainedS, ev.createdAt)
        assertEquals(listOf("approx", "${heardAt / 1000}"), ev.tags.single { it[0] == "approx" })
        assertEquals("no receipt for a stale frame", 0, g.r.ofKind(Market.RECEIPT_KIND).size)
        // queued by the node: the same, however fresh its time
        val h = rig()
        val q = Synth.report(823)
        h.b.onRelayEvent(cmdEntry(h.nowS(), listOf(Lt2.tag(q)), "flood").toJson())
        advanceTimeBy(1000)
        h.b.hear(q, flood0.copy(queued = true))
        advanceUntilIdle()
        assertEquals(0, h.m.sent.size)
        assertNotNull(h.r.ofKind(Lt2.KIND_REPORT).single().tags.singleOrNull { it[0] == "approx" })
    }

    @Test
    fun aReportGoesIntoTheOutboxAtHearingTime() = runTest {
        val g = rig()
        val p = Synth.report(824)
        g.b.hear(p, hops3)
        runCurrent()
        val st = Json.decodeFromString(BridgeState.serializer(), Json.encodeToString(BridgeState.serializer(), g.b.exportState()))
        assertEquals("before the publish delay, R21", 1, st.outbox.size)
        assertTrue(st.paths.single().p == p.hex())
        // a restart in the delay: the outbox publishes it
        val h = rig()
        h.b.importState(st)
        advanceTimeBy(10_000)
        h.b.retryOutbox()
        assertEquals(1, h.r.ofKind(Lt2.KIND_REPORT).size)
        advanceUntilIdle()
        assertEquals(0, g.b.outboxSize)
    }

    @Test
    fun fastPublishForDirectHearingsWithNothingWaiting() = runTest {
        val g = rig()
        val p = Synth.report(825)
        g.b.hear(p, direct)
        advanceTimeBy(900)
        assertEquals(1, g.r.ofKind(Lt2.KIND_REPORT).size)
        val p3 = Synth.report(826)
        g.b.hear(p3, hops3)
        advanceTimeBy(1_900)
        assertEquals("3 hops: 2 to 5 s", 1, g.r.ofKind(Lt2.KIND_REPORT).size)
        advanceUntilIdle()
        assertEquals(2, g.r.ofKind(Lt2.KIND_REPORT).size)
    }

    @Test
    fun blindLostFloodsInTheCountryScopeWithoutVia() = runTest {
        val g = rig(BridgeConfig(regions = listOf("sk-test", "sk"), region = true))
        val n = g.nowS()
        fun blind(i: Int) = NostrEvent.sign(testKey(900 + i), n, Lt2.KIND_COMMAND,
            listOf(listOf("v", "2"), listOf("route", "flood"), listOf("y", "c0de02%02x".format(i)), listOf("at", "${n + 60 + i * 10}"),
                listOf("via", "deadbeef"), listOf("blind", "1"), listOf("p", mePub), listOf("expiration", "${n + 3600}")),
            Base64.getEncoder().encodeToString(pcN(20 + i)))
        (0 until 4).forEach { g.b.onRelayEvent(blind(it).toJson()) }
        (0 until 4).forEach { startAt(g, n + 60 + it * 10); advanceUntilIdle() }
        assertEquals("3 per via in 24 h", 3, g.m.sent.size)
        assertTrue(g.m.sent.all { it.route == Route.Flood && it.region == "sk" })
    }

    @Test
    fun radioHealth() = runTest {
        val h = RadioHealth(30 * 60_000L, 0)
        assertNull(h.tick(29 * 60_000L))
        assertEquals(RadioHealth.Transition(false, "rx_silence", 31 * 60_000L), h.tick(31 * 60_000L))
        assertEquals(true, h.onRadio(32 * 60_000L)?.up)
        assertNull(h.onSend(false, "timeout", 33 * 60_000L))
        assertEquals("send_failed", h.onSend(false, "timeout", 34 * 60_000L)?.reason)
        assertEquals(true, h.onSend(true, null, 35 * 60_000L)?.up)
        assertEquals("api_says_down", h.onApi(false, 36 * 60_000L)?.reason)
        assertNull("frames of the session alone do not bring it up while the API says down", h.onRadio(37 * 60_000L))
        assertNull(h.onApi(true, 38 * 60_000L))
        assertEquals(true, h.onRadio(39 * 60_000L)?.up)
        assertEquals("down" to 0L, RadioHealth(1, 0).also { it.tick(10) }.announced().let { it.first to 0L })

        // in the bridge: radio_down announced at once, radio_up on the next packet
        val g = rig()
        g.b.onRadioApi(false)
        runCurrent()
        val a = Market.parseAnnouncement(g.r.ofKind(Market.ANNOUNCE_KIND).last(), g.nowS())!!
        assertEquals("down", a.radio!!.first)
        g.b.onRadioApi(true)
        g.b.hear(Synth.report(827), flood0)
        runCurrent()
        assertTrue(g.b.radio.up)
        assertEquals("up", Market.parseAnnouncement(g.r.ofKind(Market.ANNOUNCE_KIND).last(), g.nowS())!!.radio!!.first)
    }

    @Test
    fun queuedFramesFromMeshCoreOpenAreMarked() {
        fun ev(s: String) = MeshEvent.parse(Json.parseToJsonElement(s).jsonObject) as MeshEvent.ChannelData
        val live = ev("""{"type":"channel.data","channel":"#libretag","dataType":65356,"payload":"AA==","hops":1,"path":"a1","pathHashSize":1,"routeType":"flood","rssiDbm":-90}""")
        val drained = ev("""{"type":"channel.data","channel":"#libretag","dataType":65356,"payload":"AA==","hops":1,"pathHashSize":1,"routeType":"flood"}""")
        val withPath = setOf(Caps.RX_PATH, Caps.RX_META)
        assertNull(live.meta(withPath).queued)
        assertEquals(true, drained.meta(withPath).queued)
        assertNull("without rx_path nothing can be told", drained.meta(emptySet()).queued)
        // with rx_queued the server's flag counts, not the missing path
        val q = setOf(Caps.RX_PATH, Caps.RX_META, Capabilities.RX_QUEUED)
        assertNull(drained.meta(q).queued)
        val flagged = ev("""{"type":"channel.data","channel":"#libretag","dataType":65356,"payload":"AA==","hops":1,"path":"a1","pathHashSize":1,"routeType":"flood","rssiDbm":-90,"queued":true}""")
        assertEquals(true, flagged.meta(q).queued)
    }

    @Test
    fun scopeAndTransportCodeFromRxScope() {
        fun ev(s: String) = MeshEvent.parse(Json.parseToJsonElement(s).jsonObject) as MeshEvent.ChannelData
        val d = ev("""{"type":"channel.data","channel":"#libretag","dataType":65356,"payload":"AA==","hops":1,"path":"a1","pathHashSize":1,"routeType":"tflood","transportCode":4242,"scope":"sk-test"}""")
        val m = d.meta(setOf(Caps.RX_PATH, Caps.RX_META, Capabilities.RX_SCOPE))
        assertEquals(4242, m.transportCode)
        assertEquals("sk-test", m.scope)
        assertNull("not advertised: ignored", d.meta(setOf(Caps.RX_PATH, Caps.RX_META)).transportCode)
    }
}

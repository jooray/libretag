package app.nostrmesh

import app.nostrmesh.bridge.Airtime
import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.LoRaPreset
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.data.loadOrCreateBridgeKey
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Mailbox
import app.nostrmesh.libretag.Market
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.sha256
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
import kotlinx.serialization.json.intOrNull
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
import java.io.File
import java.util.Base64
import kotlin.random.Random

/**
 * PROTOCOL.md 4.1 on the Android bridge (parity with host/src/nostrmesh/market.py and lt2svc.py):
 * mailbox-v2.json vectors, seen-first matching, first hearing only, bridge key, counted claims,
 * format 2 routes and targeting, airtime order, receipts, announcements, subscriptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketTest {
    private val v2 = Json.parseToJsonElement(javaClass.classLoader!!.getResource("mailbox-v2.json")!!.readText()).jsonObject
    private val lt2 = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lt2.json")!!.readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun tagsOf(o: JsonObject, k: String = "tags") = o[k]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
    private val pc = lt2["commands"]!!.jsonArray[0].jsonObject.s("p").unhex()!!
    private fun pcN(i: Int) = pc.copyOf().also { it[28] = i.toByte(); it[27] = (i shr 8).toByte() }

    private val t0 = 1_763_020_900_000L
    private val pathCaps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)
    private val direct0 = RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1)
    private val hops1 = RxMeta(hops = 1, path = "ab".unhex(), pathHashSize = 1)
    private val hops2 = RxMeta(hops = 2, path = "aabb".unhex(), pathHashSize = 1)
    private val hops4 = RxMeta(hops = 4, path = "01020304".unhex(), pathHashSize = 1)

    private val me = testKey(500)
    private val mePub = testPub(500).hex()
    private val other = testKey(501)
    private val otherPub = testPub(501).hex()

    private class Rig(val b: Bridge, val m: FakeMesh, val r: FakeRelays, val clock: () -> Long)

    private fun TestScope.rig(
        cfg: BridgeConfig = BridgeConfig(), key: ByteArray? = me, pow: Boolean = false, caps: Set<String> = pathCaps, start: Long = t0,
    ): Rig {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        val clock = { start + testScheduler.currentTime }
        val b = Bridge(this, m, r, if (pow) cfg else cfg.legacyTiming(), emptyList(), clock = clock, random = Random(7),
            newKey = keySource(), bridgeKey = key)
        return Rig(b, m, r, clock)
    }

    private fun Rig.nowS() = clock() / 1000
    private suspend fun Bridge.hear(p: ByteArray, meta: RxMeta = direct0) {
        ownerAnchor(p, t0 / 1000)
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, meta)
    }

    /** A synthetic report whose commitment fits its own test key ([Synth]). */
    private fun rep(i: Int) = Synth.report(i)

    private fun entry(
        key: Int, createdS: Long, ys: List<String>, extra: List<List<String>> = emptyList(), payload: ByteArray = pc,
        at: Long? = null, via: String? = null,
        stranger: Boolean = false,
    ) = ownerEntry(createdS,
        ys.map { listOf("y", it) } + listOf(listOf("expiration", "${createdS + 48 * 3600}")) + extra +
            listOfNotNull(at?.let { listOf("at", "$it") }, via?.let { listOf("via", it) }),
        Base64.getEncoder().encodeToString(payload), if (stranger) testKey(key) else null)

    private fun fmt2(route: String, vararg p: String, r: String? = null) =
        listOf(listOf("v", "2"), listOf("route", route)) + p.map { listOf("p", it) } + listOfNotNull(r?.let { listOf("r", it) })

    private fun announcement(sk: ByteArray, nowS: Long, net: String = "") =
        NostrEvent.sign(sk, nowS, Market.ANNOUNCE_KIND, Market.announceTags(net, nowS), "{}")

    /** Runs the timed scheduler at `at` minus the claim lead; the claim is out, the send waits for `at`. */
    private fun TestScope.startAt(g: Rig, atS: Long) {
        advanceTimeBy(atS * 1000 - 5_000 - g.clock())
        g.b.timedTick()
        runCurrent()
    }

    // ------------------------------------------------------------------ shared vectors

    @Test
    fun vectors() {
        for (v in v2["proof"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(v.s("proof"), Market.claimProof(v.s("p").unhex()!!, v.s("pubkey")))
        }
        for (v in v2["hops_class"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(v.s("class"), Market.hopsClass(v["hops"]!!.jsonPrimitive.intOrNull))
        }
        for (v in v2["targeting"]!!.jsonArray.map { it.jsonObject }) {
            val t = Market.targeting(tagsOf(v))
            assertEquals(v.s("note"), v["valid"]!!.jsonPrimitive.boolean, t != null)
            val parsed = v["parsed"]?.jsonObject ?: continue
            assertEquals(parsed["format"]!!.jsonPrimitive.int, t!!.fmt)
            assertEquals(parsed.s("route"), t.route)
            assertEquals(parsed["p"]!!.jsonArray.map { it.jsonPrimitive.content }, t.p)
            assertEquals(parsed["r"]!!.jsonPrimitive.let { if (it.isString) it.content else null }, t.r)
        }
        for (v in v2["lock"]!!.jsonArray.map { it.jsonObject }) {
            for (k in v["keys"]!!.jsonArray.map { it.jsonObject }) {
                assertEquals(k.s("note"), k["fits"]!!.jsonPrimitive.boolean, Lt2.commitMatches(k.s("pubkey"), v.s("p").unhex()!!))
            }
        }
        val bridge = v2.s("bridge_pubkey")
        assertEquals(bridge, NostrEvent.xonly(vectorKey).hex())
        for (v in v2["events"]!!.jsonArray.map { it.jsonObject }) {
            val ev = NostrEvent.fromJson(v["event"]!!.jsonObject)!!
            assertTrue(ev.verify())
            val t = Market.targeting(ev.tags)
            assertEquals(v.s("note"), v["valid"]!!.jsonPrimitive.boolean, t != null)
            if (t != null) assertEquals(v.s("note"), v["for_bridge"]!!.jsonPrimitive.boolean, t.open || bridge in t.p)
        }
    }

    private val vectorKey = sha256("mailbox v2 vector 100".toByteArray())

    /** Each vector event at a bridge with the vectors' bridge key, then the vector report heard: stored and locked? */
    @Test
    fun vectorEventsThroughTheBridge() = runTest {
        val lock = v2["lock"]!!.jsonArray[0].jsonObject
        val x = lock.s("x")
        for (v in v2["events"]!!.jsonArray.map { it.jsonObject }) {
            val ev = NostrEvent.fromJson(v["event"]!!.jsonObject)!!
            val g = rig(key = vectorKey, start = (ev.createdAt + 10) * 1000)
            g.b.onRelayEvent(ev.toJson(), "wss://a")
            advanceTimeBy(1000)
            g.b.onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, lock.s("p").unhex()!!, direct0)
            advanceUntilIdle()
            val want = v["valid"]!!.jsonPrimitive.boolean && v["for_bridge"]!!.jsonPrimitive.boolean
            val stored = if (ev.content.isEmpty()) ev.pubkey in g.b.mailbox.keysOfY(x)
            else ev.id in g.b.mailbox.export().commands.map { it.id } || g.m.sent.isNotEmpty()
            assertEquals(v.s("note"), want, stored)
            if (stored && ev.tags.none { it[0] == "at" }) {
                assertEquals(v.s("note"), v["signed_by_pk_n"]!!.jsonPrimitive.boolean, ev.pubkey in g.b.mailbox.locked(x))
            }
        }
    }

    @Test
    fun defaults() {
        val c = BridgeConfig()
        assertEquals(200_000, c.mailboxCap)
        assertEquals(200_000, Mailbox.DEFAULT_CAP)
        assertEquals(2_000L, c.reportHoldMinMs)
        assertEquals(5_000L, c.reportHoldMaxMs)
        assertTrue("publish floor above the claim wait", c.reportHoldMinMs > c.electionMs)
        assertEquals(0.5, c.timedAirtimeShare, 0.0)
        assertEquals(6, c.openFloodsPerHour)
        assertFalse(c.receipts)
        assertEquals(120, c.receiptsPerHour)
        assertTrue(c.acceptOpen && c.announce)
        assertTrue(c.peers.isEmpty() && c.networks.isEmpty())
        assertEquals(60_000L, c.anchorLeadMs)
        assertEquals(60_000L, c.lateMaxMs)
        assertEquals(8, c.keysPerY)
    }

    // ------------------------------------------------------------------ Q1, Q5

    @Test
    fun entrySeenFirstWinsOverNewerCreatedAt() = runTest {
        val g = rig()
        val p = rep(1)
        val n = g.nowS()
        g.b.onRelayEvent(entry(10, n - 600, listOf(Lt2.tag(p)), payload = pcN(1)).toJson())
        advanceTimeBy(100)
        g.b.onRelayEvent(entry(11, n, listOf(Lt2.tag(p)), payload = pcN(2)).toJson()) // newer created_at, seen later
        advanceTimeBy(1000)
        g.b.hear(p)
        advanceUntilIdle()
        assertEquals(pcN(1).hex(), g.m.sent.single().payload.hex())
    }

    @Test
    fun onlyTheFirstHearingOfXTriggers() = runTest {
        val g = rig()
        val p = rep(2)
        g.b.hear(p, hops2) // no entry yet
        advanceTimeBy(1000)
        // a stranger copied the y from the relays after the report (the owner's key may: a late entry, 4.2)
        g.b.onRelayEvent(entry(12, g.nowS(), listOf(Lt2.tag(p)), stranger = true).toJson())
        advanceTimeBy(1000)
        g.b.hear(p, hops1) // a later flood copy
        g.b.hear(p, direct0) // the tag's retransmission
        advanceUntilIdle()
        assertEquals(0, g.m.sent.size)
        assertEquals("refused: not a key the report locked", 0, g.b.mailbox.size)
    }

    // ------------------------------------------------------------------ bridge key and claims

    @Test
    fun claimsSignedWithBridgeKeyWithProofAndReportAfterTwoSeconds() = runTest {
        val g = rig()
        val p = rep(3)
        g.b.onRelayEvent(entry(13, g.nowS(), listOf(Lt2.tag(p))).toJson())
        advanceTimeBy(1000)
        g.b.hear(p)
        runCurrent()
        val claim = g.r.ofKind(Lt2.KIND_CLAIM).single()
        assertEquals(mePub, claim.pubkey)
        assertEquals(listOf(listOf("y", Lt2.tag(p)), listOf("hops", "0"), listOf("proof", Market.claimProof(p, mePub))), claim.tags)
        advanceTimeBy(1_999)
        assertEquals("no report before the 2 s floor", 0, g.r.ofKind(Lt2.KIND_REPORT).size)
        advanceUntilIdle()
        val report = g.r.ofKind(Lt2.KIND_REPORT).single()
        assertTrue("reports keep a fresh key", report.pubkey != mePub)
        assertEquals(1, g.m.sent.size)
    }

    @Test
    fun doneClaimSignedWithBridgeKey() = runTest {
        val g = rig()
        val pn = rep(4); val pn1 = rep(5)
        g.b.hear(pn, hops2)
        advanceUntilIdle()
        val n = g.nowS()
        val grp = "0123456789abcdef"
        g.b.onRelayEvent(entry(14, n, listOf("c0de0001"), listOf(listOf("g", grp)), pcN(1), at = n + 900, via = Lt2.tag(pn)).toJson())
        g.b.onRelayEvent(entry(15, n, listOf(Lt2.tag(pn1)), listOf(listOf("g", grp)), pcN(2)).toJson())
        advanceTimeBy(200_000)
        g.b.hear(pn1, hops2)
        advanceUntilIdle()
        val done = g.r.ofKind(Lt2.KIND_CLAIM).single { c -> c.tags.any { it[0] == "done" } }
        assertEquals(mePub, done.pubkey)
    }

    /** Runs one untimed delivery with a rival claim [rival] arriving during the election; true if this bridge sent. */
    private fun TestScope.untimedWithRival(
        cfg: BridgeConfig = BridgeConfig(), extra: List<List<String>> = emptyList(), heard: RxMeta = hops1,
        before: (Rig, ByteArray) -> Unit = { _, _ -> }, rival: (Rig, ByteArray) -> NostrEvent?,
    ): Boolean {
        val g = rig(cfg)
        val p = rep(6)
        kotlinx.coroutines.runBlocking {
            g.b.onRelayEvent(entry(16, g.nowS(), listOf(Lt2.tag(p)), extra).toJson())
        }
        before(g, p)
        advanceTimeBy(1000)
        kotlinx.coroutines.runBlocking { g.b.hear(p, heard) }
        runCurrent()
        rival(g, p)?.let { kotlinx.coroutines.runBlocking { g.b.onRelayEvent(it.toJson(), "wss://a") } }
        advanceUntilIdle()
        return g.m.sent.isNotEmpty()
    }

    @Test
    fun onlyCountedClaimsWin() = runTest {
        val y = Lt2.tag(rep(6))
        fun claim(g: Rig, p: ByteArray, hops: Int = 0, proof: String? = Market.claimProof(p, otherPub)) =
            Lt2.claimEvent(y, hops, g.nowS(), other, proof)
        val announce = { g: Rig, _: ByteArray -> kotlinx.coroutines.runBlocking { g.b.onRelayEvent(announcement(other, g.nowS()).toJson()) } }

        assertTrue("random key (older bridge): ignored", untimedWithRival { g, _ -> Lt2.claimEvent(y, 0, g.nowS(), testKey(88)) })
        assertTrue("unannounced key with a proof: ignored", untimedWithRival { g, p -> claim(g, p) })
        assertFalse("configured peer, no proof needed", untimedWithRival(BridgeConfig(peers = listOf(otherPub))) { g, p -> claim(g, p, proof = null) })
        assertFalse("peer as npub", untimedWithRival(BridgeConfig(peers = listOf(app.nostrmesh.nostr.Bech32.npub(testPub(501))))) { g, p -> claim(g, p, proof = null) })
        assertFalse("announced key with the right proof", untimedWithRival(before = announce) { g, p -> claim(g, p) })
        assertTrue("announced key with a wrong proof", untimedWithRival(before = announce) { g, _ -> claim(g, rep(7)) })
        assertTrue("announced key without a proof", untimedWithRival(before = announce) { g, p -> claim(g, p, proof = null) })
        assertTrue("expired announcement", untimedWithRival(before = { g, _ ->
            kotlinx.coroutines.runBlocking { g.b.onRelayEvent(announcement(other, g.nowS() - 8 * 86400).toJson()) }
        }) { g, p -> claim(g, p) })
        assertTrue("forged announcement signature", untimedWithRival(before = { g, _ ->
            val a = announcement(other, g.nowS())
            kotlinx.coroutines.runBlocking { g.b.onRelayEvent(a.copy(sig = announcement(me, g.nowS()).sig).toJson()) }
        }) { g, p -> claim(g, p) })
        // named in p: counts with a proof; the index in p comes before hops
        assertFalse("named first, farther away", untimedWithRival(extra = fmt2("flood", otherPub, mePub), heard = direct0) { g, p -> claim(g, p, hops = 3) })
        assertTrue("named second, nearer", untimedWithRival(extra = fmt2("flood", mePub, otherPub)) { g, p -> claim(g, p, hops = 0) })
        assertTrue("named, wrong proof", untimedWithRival(extra = fmt2("flood", otherPub, mePub)) { g, _ -> claim(g, rep(7)) })
    }

    @Test
    fun timedClaimsCountOnlyFromPeers() = runTest {
        for ((peer, sent) in listOf(false to true, true to false)) {
            val g = rig(BridgeConfig(peers = if (peer) listOf(otherPub) else emptyList()))
            val via = rep(8)
            g.b.hear(via, hops2)
            g.b.onRelayEvent(announcement(other, g.nowS()).toJson())
            advanceUntilIdle()
            val n = g.nowS()
            g.b.onRelayEvent(entry(17, n, listOf("c0de0002"), at = n + 900, via = Lt2.tag(via)).toJson())
            startAt(g, n + 900)
            assertEquals("no proof on a timed claim", null, g.r.ofKind(Lt2.KIND_CLAIM).single().tags.firstOrNull { it[0] == "proof" })
            g.b.onRelayEvent(Lt2.claimEvent("c0de0002", 0, g.nowS(), other, "00".repeat(32)).toJson(), "wss://a")
            advanceUntilIdle()
            assertEquals("peer=$peer", sent, g.m.sent.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ format 2: route and targeting

    @Test
    fun formatTwoRouteBinds() = runTest {
        data class Case(val route: String, val heard: RxMeta, val want: String?)
        val cases = listOf(
            Case("near", hops2, null), Case("near", direct0, "zero"),
            Case("direct", direct0, "zero"), Case("direct", hops2, "direct bbaa"), Case("direct", hops4, null),
            Case("direct", RxMeta(hops = 2), null), // no path known
            Case("flood", hops4, "flood"), Case("flood", direct0, "zero"),
        )
        for (c in cases) {
            val g = rig()
            val p = rep(9)
            g.b.onRelayEvent(entry(18, g.nowS(), listOf(Lt2.tag(p)), fmt2(c.route, mePub)).toJson())
            advanceTimeBy(1000)
            g.b.hear(p, c.heard)
            advanceUntilIdle()
            val got = g.m.sent.singleOrNull()?.route?.let {
                when (it) { Route.ZeroHop -> "zero"; Route.Flood -> "flood"; is Route.Direct -> "direct " + it.path.hex() }
            }
            assertEquals("$c", c.want, got)
            if (c.want == null) {
                assertEquals("$c: no claim", 0, g.r.ofKind(Lt2.KIND_CLAIM).size)
                assertEquals("$c: y not answered", 1, g.b.mailbox.size)
                assertTrue(g.b.mailbox.match(Lt2.tag(p), g.clock() + 1, g.clock(), setOf(Synth.pk(9))).isNotEmpty())
            }
        }
        val p = rep(10)
        // without tx_path_data no direct sending
        val g2 = rig(caps = setOf(Caps.RX_PATH))
        g2.b.onRelayEvent(entry(18, g2.nowS(), listOf(Lt2.tag(p)), fmt2("direct", mePub)).toJson())
        advanceTimeBy(1000)
        g2.b.hear(p, hops2)
        advanceUntilIdle()
        assertEquals(0, g2.m.sent.size)
    }

    @Test
    fun timedEntryTreatsDirectAsNear() = runTest {
        for ((heard, want) in listOf(hops2 to null, direct0 to Route.ZeroHop)) {
            val g = rig()
            val via = rep(11)
            g.b.hear(via, heard)
            advanceUntilIdle()
            val n = g.nowS()
            g.b.onRelayEvent(entry(20, n, listOf("c0de0003"), fmt2("direct", mePub), at = n + 900, via = Lt2.tag(via)).toJson())
            startAt(g, n + 900)
            advanceUntilIdle()
            assertEquals("$heard", want, g.m.sent.singleOrNull()?.route)
            if (want == null) {
                assertEquals(0, g.r.ofKind(Lt2.KIND_CLAIM).size)
                assertEquals(0, g.b.mailbox.size)
                // its y was not marked answered: an entry under the same y can still be added
                g.b.onRelayEvent(entry(21, g.nowS(), listOf("c0de0003")).toJson())
                assertEquals(1, g.b.mailbox.size)
            }
        }
    }

    @Test
    fun storedOnlyWhenForThisBridge() = runTest {
        fun stored(cfg: BridgeConfig, extra: List<List<String>>, key: ByteArray? = me): Boolean {
            val g = rig(cfg, key)
            kotlinx.coroutines.runBlocking { g.b.onRelayEvent(entry(22, g.nowS(), listOf("c0de0004"), extra).toJson()) }
            return g.b.mailbox.size == 1
        }
        val base = BridgeConfig()
        val noV = rig(base)
        noV.b.onRelayEvent(NostrEvent.sign(testKey(22), noV.nowS(), Lt2.KIND_COMMAND, listOf(listOf("y", "c0de0004"),
            listOf("route", "flood")), Base64.getEncoder().encodeToString(pc)).toJson())
        assertEquals("format 1 (no v): refused", 0, noV.b.mailbox.size)
        assertTrue("open format 2", stored(base, fmt2("flood")))
        assertTrue("names this bridge", stored(base, fmt2("flood", otherPub, mePub)))
        assertFalse("names others only", stored(base, fmt2("flood", otherPub)))
        assertFalse("no bridge key", stored(base, fmt2("flood", mePub), key = null))
        assertFalse("malformed route", stored(base, fmt2("far")))
        val closed = base.copy(acceptOpen = false)
        assertFalse("open off: open entry", stored(closed, fmt2("flood")))
        assertTrue("open off: named", stored(closed, fmt2("flood", mePub)))
        val sk = base.copy(networks = listOf("sk"))
        assertTrue("own network", stored(sk, fmt2("flood", mePub, r = "sk")))
        assertFalse("other network", stored(sk, fmt2("flood", mePub, r = "cz")))
        assertTrue("no r", stored(sk, fmt2("flood", mePub)))
        assertTrue("no networks configured: any r", stored(base, fmt2("flood", mePub, r = "cz")))
    }

    // ------------------------------------------------------------------ airtime order

    @Test
    fun timedTransmissionsUseAtMostTheirShare() = runTest {
        val ms = Airtime.ms(Airtime.channelPacketBytes(Lt2.COMMAND_LEN), LoRaPreset.SK)
        val g = rig(BridgeConfig(ltAirtimeFraction = 2.5 * ms / 3_600_000))
        val v0 = rep(12); val v1 = rep(13); val p = rep(14)
        g.b.hear(v0); g.b.hear(v1)
        advanceUntilIdle()
        val n = g.nowS()
        g.b.onRelayEvent(entry(23, n, listOf("c0de0005"), payload = pcN(1), at = n + 600, via = Lt2.tag(v0)).toJson())
        g.b.onRelayEvent(entry(24, n, listOf("c0de0006"), payload = pcN(2), at = n + 900, via = Lt2.tag(v1)).toJson())
        g.b.onRelayEvent(entry(25, n, listOf(Lt2.tag(p)), payload = pcN(3)).toJson())
        startAt(g, n + 600); advanceUntilIdle()
        startAt(g, n + 900); advanceUntilIdle()
        g.b.hear(p)
        advanceUntilIdle()
        assertEquals(listOf(pcN(1), pcN(3)).map { it.hex() }, g.m.sent.map { it.payload.hex() })
        assertEquals(1L, g.b.status()["counters"]!!.jsonObject["tx_airtime_timed_exhausted"]!!.jsonPrimitive.longOrNull)
        val st = Json.decodeFromString(BridgeState.serializer(), Json.encodeToString(BridgeState.serializer(), g.b.exportState()))
        assertEquals(1, st.ltTimedAirtime.size)
    }

    @Test
    fun openFloodsCappedPerHour() = runTest {
        val g = rig(BridgeConfig(openFloodsPerHour = 2))
        val ps = (0 until 5).map { rep(20 + it) }
        val n = g.nowS()
        ps.take(3).forEachIndexed { i, p -> g.b.onRelayEvent(entry(30 + i, n, listOf(Lt2.tag(p)), payload = pcN(i)).toJson()) }
        g.b.onRelayEvent(entry(33, n, listOf(Lt2.tag(ps[3])), fmt2("flood", mePub), pcN(3)).toJson()) // names this bridge
        g.b.onRelayEvent(entry(34, n, listOf(Lt2.tag(ps[4])), payload = pcN(4)).toJson()) // heard directly: zero-hop
        advanceTimeBy(1000)
        for (i in 0 until 4) { g.b.hear(ps[i], hops2); advanceUntilIdle() }
        g.b.hear(ps[4], direct0); advanceUntilIdle()
        assertEquals(listOf(0, 1, 3, 4).map { pcN(it).hex() }, g.m.sent.map { it.payload.hex() })
        assertEquals(listOf(Route.Flood, Route.Flood, Route.Flood, Route.ZeroHop), g.m.sent.map { it.route })
        assertEquals("the blocked entry stays", 1, g.b.mailbox.size)
        // the count survives a restart
        val state = Json.decodeFromString(BridgeState.serializer(), Json.encodeToString(BridgeState.serializer(), g.b.exportState()))
        assertEquals(2, state.openFloods.size)
        val g2 = rig(BridgeConfig(openFloodsPerHour = 2))
        g2.b.importState(state)
        val p5 = rep(25)
        g2.b.onRelayEvent(entry(35, g2.nowS(), listOf(Lt2.tag(p5)), payload = pcN(5)).toJson())
        advanceTimeBy(1000)
        g2.b.hear(p5, hops2)
        advanceUntilIdle()
        assertEquals(0, g2.m.sent.size)
        // an hour later there is room again
        advanceTimeBy(3600_000)
        val p6 = rep(26)
        g2.b.onRelayEvent(entry(36, g2.nowS(), listOf(Lt2.tag(p6)), payload = pcN(6)).toJson())
        advanceTimeBy(1000)
        g2.b.hear(p6, hops2)
        advanceUntilIdle()
        assertEquals(1, g2.m.sent.size)
    }

    // ------------------------------------------------------------------ receipts, announcements, subscriptions

    @Test
    fun receiptsOnlyWhenTurnedOn() = runTest {
        val off = rig()
        off.b.hear(rep(40))
        advanceUntilIdle()
        assertEquals(0, off.r.ofKind(Market.RECEIPT_KIND).size)

        val noKey = rig(BridgeConfig(receipts = true), key = null)
        noKey.b.hear(rep(40))
        advanceUntilIdle()
        assertEquals(0, noKey.r.ofKind(Market.RECEIPT_KIND).size)

        val g = rig(BridgeConfig(receipts = true, receiptsPerHour = 2))
        val p = rep(41)
        val heardS = g.nowS()
        g.b.hear(p, hops2)
        runCurrent()
        advanceTimeBy(1_999)
        assertEquals("after the 2 s floor", 0, g.r.ofKind(Market.RECEIPT_KIND).size)
        advanceUntilIdle()
        val rc = g.r.ofKind(Market.RECEIPT_KIND).single()
        assertTrue(rc.verify())
        assertEquals(mePub, rc.pubkey)
        assertEquals(heardS, rc.createdAt)
        assertEquals(Market.receiptTags(Lt2.tag(p), 2, heardS), rc.tags)
        assertEquals("", rc.content)
        g.b.hear(p, direct0) // a copy
        g.b.hear(rep(42), direct0.copy(receivedAtMs = g.clock() - 600_000)) // a stale queued frame
        g.b.hear(ByteArray(Lt2.BLE_LEN) { 7 }) // not a 45 B report
        advanceUntilIdle()
        assertEquals(1, g.r.ofKind(Market.RECEIPT_KIND).size)
        g.b.hear(rep(43)); g.b.hear(rep(44))
        advanceUntilIdle()
        assertEquals("120 per hour (here 2)", 2, g.r.ofKind(Market.RECEIPT_KIND).size)
        assertEquals(listOf("y", "hops", "expiration"), g.r.ofKind(Market.RECEIPT_KIND).last().tags.map { it[0] })
    }

    @Test
    fun announcementsPerNetworkAtStartAndDaily() = runTest {
        val g = rig(BridgeConfig(networks = listOf("sk", "cz"), bridgeName = "test", appVersion = "9.9", regionName = "sk", region = true))
        assertTrue(g.b.announceIfDue())
        assertFalse(g.b.announceIfDue())
        val evs = g.r.ofKind(Market.ANNOUNCE_KIND)
        assertEquals(2, evs.size)
        for ((ev, net) in evs.zip(listOf("sk", "cz"))) {
            assertTrue(ev.verify())
            assertEquals(mePub, ev.pubkey)
            val a = Market.parseAnnouncement(ev, g.nowS())!!
            assertEquals(net, a.network)
            assertEquals("sk", a.region)
            assertTrue(a.open)
            assertFalse(a.receipts)
            assertEquals(listOf(0, 60), a.signed) // legacyTiming() rig: anchor lead 0
            assertTrue(ev.tags.none { it[0] == "pow" })
            assertEquals(g.nowS() + 7 * 86400, a.expires)
            assertTrue(listOf("relay", "wss://nostr.example.com") in ev.tags)
            val c = Json.parseToJsonElement(ev.content).jsonObject
            assertEquals("test", c.s("name"))
            assertEquals("9.9", c.s("version"))
            assertEquals(Bridge.ADAPTER, c.s("adapter"))
            assertTrue(c["zero_hop"]!!.jsonPrimitive.boolean && c["direct"]!!.jsonPrimitive.boolean)
        }
        advanceTimeBy(24 * 3600_000L)
        assertTrue(g.b.announceIfDue())
        assertEquals(4, g.r.ofKind(Market.ANNOUNCE_KIND).size)

        val plain = rig()
        assertTrue(plain.b.announceIfDue())
        assertEquals(listOf("d", ""), plain.r.ofKind(Market.ANNOUNCE_KIND).single().tags[0])
        assertFalse("no key, no announcement", rig(key = null).b.announceIfDue())
        assertFalse(rig(BridgeConfig(announce = false)).b.announceIfDue())
    }

    @Test
    fun parseAnnouncementRules() {
        val nowS = t0 / 1000
        assertNotNull(Market.parseAnnouncement(announcement(other, nowS, "sk"), nowS))
        assertNull("expired", Market.parseAnnouncement(announcement(other, nowS - 8 * 86400), nowS))
        assertNull("bad network", Market.parseAnnouncement(NostrEvent.sign(other, nowS, Market.ANNOUNCE_KIND,
            Market.announceTags("SK", nowS), ""), nowS))
        assertNull("other service", Market.parseAnnouncement(NostrEvent.sign(other, nowS, Market.ANNOUNCE_KIND,
            listOf(listOf("d", ""), listOf("k", "1"), listOf("expiration", "${nowS + 60}")), ""), nowS))
    }

    @Test
    fun subscriptionsFollowAcceptOpen() = runTest {
        val open = rig().b.subscriptions()
        val all = open["nm-cmd"]!!.single()
        assertEquals(setOf("kinds", "since"), all.keys)
        val ann = open["nm-ann"]!!.single()
        assertEquals("38244", ann["kinds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("4244", ann["#k"]!!.jsonArray[0].jsonPrimitive.content)

        val closed = rig(BridgeConfig(acceptOpen = false, networks = listOf("sk"))).b.subscriptions()["nm-cmd"]!!
        assertEquals(2, closed.size)
        assertEquals(mePub, closed[0]["#p"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("sk", closed[1]["#r"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(closed.all { it["kinds"]!!.jsonArray.single().jsonPrimitive.int == Lt2.KIND_COMMAND })
        assertEquals(1, rig(BridgeConfig(acceptOpen = false)).b.subscriptions()["nm-cmd"]!!.size)
    }

    @Test
    fun bridgeKeyIsMadeOnceAndKept() {
        val dir = kotlin.io.path.createTempDirectory("bridgekey").toFile()
        try {
            val f = File(dir, "bridge.key")
            val k1 = loadOrCreateBridgeKey(f)
            assertEquals(32, k1.size)
            assertTrue(k1.contentEquals(loadOrCreateBridgeKey(f)))
            f.writeText("not a key")
            val k2 = loadOrCreateBridgeKey(f)
            assertFalse(k1.contentEquals(k2))
            assertTrue(k2.contentEquals(loadOrCreateBridgeKey(f)))
        } finally {
            dir.deleteRecursively()
        }
    }
}

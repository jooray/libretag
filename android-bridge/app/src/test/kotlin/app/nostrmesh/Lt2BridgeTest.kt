package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Election
import app.nostrmesh.libretag.Lt2
import app.nostrmesh.libretag.Mailbox
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

/** LT2 on the bridge: variant B uplink, command mailbox, election (PROTOCOL.md 4, SPEC 6.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class Lt2BridgeTest {
    private val vectors = Json.parseToJsonElement(javaClass.classLoader!!.getResource("lt2.json")!!.readText()).jsonObject
    private fun vec(section: String, i: Int) = vectors[section]!!.jsonArray[i].jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private val report = vec("reports", 0)
    private val p = report.s("p").unhex()!!
    private val y = report.s("tag")
    private val pc = vec("commands", 0).s("p").unhex()!!

    private val t0 = 1_763_020_900_000L
    private var now = t0
    private val pathCaps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)
    private val direct0 = RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1, routeType = "flood")

    private fun TestScope.bridge(caps: Set<String> = pathCaps, cfg: BridgeConfig = BridgeConfig()): Triple<Bridge, FakeMesh, FakeRelays> {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        return Triple(Bridge(this, m, r, cfg.legacyTiming(), emptyList(), clock = { now }, random = Random(7), newKey = keySource()), m, r)
    }

    private fun commandEvent(ys: List<String> = listOf(y), flood: Boolean = false, createdS: Long = now / 1000, exp: Long? = createdS + 48 * 3600, key: Int = 50) =
        ownerEntry(createdS,
            ys.map { listOf("y", it) } + listOf(listOf("route", if (flood) "flood" else "near")) +
                listOfNotNull(exp?.let { listOf("expiration", it.toString()) }),
            Base64.getEncoder().encodeToString(pc), if (key == 50) null else testKey(key))

    private suspend fun Bridge.hear(payload: ByteArray, meta: RxMeta = direct0) {
        ownerAnchor(payload, now / 1000)
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, payload, meta)
    }

    // ------------------------------------------------------------------ uplink

    @Test
    fun reportTimeFollowsTheSharedVectors() {
        for (e in vectors["report_time"]!!.jsonArray) {
            val v = e.jsonObject
            val tags = v["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
            val ev = NostrEvent.sign(testKey(9), v["created_at"]!!.jsonPrimitive.content.toLong(), Lt2.KIND_REPORT, tags, "")
            val until = v["until"]!!.jsonPrimitive.content.toLongOrNull()
            assertEquals(v.s("name"), v.s("from").toLong() to until, Lt2.reportTime(ev))
        }
    }

    @Test
    fun approxReportEventEndsAtTheLatestTime() {
        val ev = Lt2.reportEvent(p, 1_800_000_600, testKey(9), approxFromS = 1_800_000_000)
        assertEquals(1_800_000_600L, ev.createdAt)
        assertTrue(listOf("approx", "1800000000") in ev.tags)
        assertEquals(1_800_000_000L to 1_800_000_600L, Lt2.reportTime(ev))
        assertEquals(1_800_000_600L to null, Lt2.reportTime(Lt2.reportEvent(p, 1_800_000_600, testKey(9))))
    }

    @Test
    fun reportPublishedAsKind4243UnderFreshKeyOnce() = runTest {
        val (b, m, r) = bridge()
        b.hear(p)
        b.hear(p, RxMeta(hops = 2)) // a second flood copy
        b.hear(vec("reports", 1).s("p").unhex()!!)
        advanceUntilIdle()
        val evs = r.ofKind(Lt2.KIND_REPORT)
        assertEquals(2, evs.size)
        val ev = evs.first { it.tags[0][1] == y }
        assertTrue(ev.verify())
        assertEquals(listOf("y", y), ev.tags[0])
        assertEquals("expiration", ev.tags[1][0])
        assertEquals(now / 1000 + Lt2.REPORT_TTL_S, ev.tags[1][1].toLong())
        assertEquals(now / 1000 + 14 * 86400, ev.tags[1][1].toLong())
        assertEquals(p.hex(), Base64.getDecoder().decode(ev.content).hex())
        assertTrue("fresh key per event", evs[0].pubkey != evs[1].pubkey)
        assertEquals(0, m.sent.size) // uplink never transmits
    }

    @Test
    fun reportSkippedWhenAlreadyOnTwoRelays() = runTest {
        val (b, _, r) = bridge()
        val content = Base64.getEncoder().encodeToString(p)
        val other = Lt2.reportEvent(p, now / 1000, testKey(77))
        b.onRelayEvent(other.toJson(), "wss://a")
        b.onRelayEvent(other.toJson(), "wss://b")
        // same tag, different content (forged): does not silence us
        val forged = NostrEvent.sign(testKey(78), now / 1000, Lt2.KIND_REPORT, listOf(listOf("y", y)), content.reversed())
        b.onRelayEvent(forged.toJson(), "wss://a")
        b.onRelayEvent(forged.toJson(), "wss://b")
        b.hear(p)
        val p2 = vec("reports", 2).s("p").unhex()!!
        b.onRelayEvent(NostrEvent.sign(testKey(79), now / 1000, Lt2.KIND_REPORT, listOf(listOf("y", Lt2.tag(p2))),
            Base64.getEncoder().encodeToString(p2)).toJson(), "wss://a") // only one relay so far
        b.hear(p2)
        advanceUntilIdle()
        val mine = r.ofKind(Lt2.KIND_REPORT)
        assertEquals(listOf(Lt2.tag(p2)), mine.map { it.tags[0][1] })
    }

    @Test
    fun uplinkDisabledPublishesNothing() = runTest {
        val (b, _, r) = bridge(cfg = BridgeConfig(uplinkProfiles = setOf("road.report")))
        b.hear(p)
        advanceUntilIdle()
        assertEquals(0, r.published.size)
    }

    // ------------------------------------------------------------------ mailbox

    @Test
    fun commandKnownBeforeReportGoesOnceZeroHop() = runTest {
        val (b, m, r) = bridge()
        b.onRelayEvent(commandEvent().toJson(), "wss://a")
        assertEquals(1, b.mailbox.size)
        now += 1000
        b.hear(p)
        b.hear(p, RxMeta(hops = 1, path = "ab".unhex(), pathHashSize = 1)) // later flood copy
        advanceUntilIdle()
        val s = m.sent.single()
        assertEquals(Frames.CHANNEL_LIBRETAG, s.channel)
        assertEquals(Frames.DATA_TYPE_LIBRETAG, s.dataType)
        assertEquals(pc.hex(), s.payload.hex())
        assertEquals(Route.ZeroHop, s.route)
        assertEquals("the claim round before every untimed send (PROTOCOL.md 4.1), zero-hop too", 1, r.ofKind(Lt2.KIND_CLAIM).size)
        assertEquals(0, b.mailbox.size)
        // the same report heard again later: nothing
        now += 10_000
        b.hear(p)
        advanceUntilIdle()
        assertEquals(1, m.sent.size)
    }

    @Test
    fun commandLearnedAfterTheReportIsNotSent() = runTest {
        val (b, m, _) = bridge()
        // the server heard the report 2 s ago; the command arrives now
        b.onRelayEvent(commandEvent().toJson())
        b.hear(p, direct0.copy(receivedAtMs = now - 2000))
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
    }

    @Test
    fun replayedReportTriggersNoCommand() = runTest {
        val (b, m, _) = bridge()
        b.onRelayEvent(commandEvent().toJson())
        now += 3600_000
        b.hear(p, direct0.copy(receivedAtMs = now - 600_000)) // heard while we were away
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(1, b.mailbox.size)
    }

    @Test
    fun floodUnlessHeardDirectlyWithoutHopLimit() = runTest {
        // 0.7.3: no 3-hop limit and no direct path; 4 hops (or 20) floods
        for (meta in listOf(RxMeta(hops = 4, path = "01020304".unhex(), pathHashSize = 1),
            RxMeta(hops = 3, path = "aaaabbbbcccc".unhex(), pathHashSize = 2), RxMeta(hops = 20), RxMeta())) {
            val (b, m, _) = bridge()
            b.onRelayEvent(commandEvent().toJson())
            now += 1000
            b.hear(p, meta)
            advanceUntilIdle()
            assertEquals(meta.toString(), Route.Flood, m.sent.single().route)
        }
    }

    @Test
    fun capabilityFallback() = runTest {
        // no extensions at all: zero-hop impossible, so flood even when heard directly
        val (b, m, _) = bridge(caps = emptySet())
        b.onRelayEvent(commandEvent().toJson())
        now += 1000
        b.hear(p, RxMeta(hops = 0))
        advanceUntilIdle()
        assertEquals(Route.Flood, m.sent.single().route)

        // tx_path_data without rx_path: zero-hop from the hop count, otherwise flood
        val (b2, m2, _) = bridge(caps = setOf(Caps.TX_PATH_DATA))
        b2.onRelayEvent(commandEvent().toJson())
        now += 1000
        b2.hear(p, RxMeta(hops = 1, path = "ab".unhex(), pathHashSize = 1))
        advanceUntilIdle()
        assertEquals(Route.Flood, m2.sent.single().route)

        val (b3, m3, _) = bridge(caps = setOf(Caps.TX_PATH_DATA))
        b3.onRelayEvent(commandEvent().toJson())
        now += 1000
        b3.hear(p, RxMeta(hops = 0))
        advanceUntilIdle()
        assertEquals(Route.ZeroHop, m3.sent.single().route)
    }

    @Test
    fun routeTagNoLongerMatters() = runTest {
        val (b, m, _) = bridge()
        b.onRelayEvent(commandEvent(flood = true).toJson())
        now += 1000
        b.hear(p, direct0)
        advanceUntilIdle()
        assertEquals(Route.ZeroHop, m.sent.single().route)
    }

    @Test
    fun expirationHonouredAndCappedAt48h() {
        val nowMs = t0
        val nowS = nowMs / 1000
        assertNull(Mailbox.parse(commandEvent(createdS = nowS - 3600, exp = nowS - 1), nowMs))
        assertNull("older than 48 h", Mailbox.parse(commandEvent(createdS = nowS - 49 * 3600, exp = nowS + 3600), nowMs))
        val c = Mailbox.parse(commandEvent(createdS = nowS, exp = nowS + 30 * 86400), nowMs)!!
        assertEquals(nowMs + 48 * 3600_000L, c.expiresAtMs)
        assertNotNull(Mailbox.parse(commandEvent(createdS = nowS, exp = null), nowMs))
        assertNull("wrong size", Mailbox.parse(NostrEvent.sign(testKey(1), nowS, Lt2.KIND_COMMAND, listOf(listOf("y", y)), "AAAA"), nowMs))
        assertNull("no y", Mailbox.parse(NostrEvent.sign(testKey(1), nowS, Lt2.KIND_COMMAND, emptyList(), Base64.getEncoder().encodeToString(pc)), nowMs))
        // match respects expiry
        val mb = Mailbox()
        val short = Mailbox.parse(commandEvent(createdS = nowS, exp = nowS + 60), nowMs)!!
        mb.add(short, nowMs)
        assertTrue(mb.match(y, nowMs + 1, nowMs + 1, setOf(short.pubkey)).isNotEmpty())
        assertTrue(mb.match(y, nowMs + 61_000, nowMs + 61_000, setOf(short.pubkey)).isEmpty())
    }

    @Test
    fun mailboxMatchingAndCap() {
        val mb = Mailbox(cap = 3)
        fun cmd(id: String, ys: List<String>, known: Long, created: Long = 1) =
            Mailbox.Command(id, ys, false, pc, known, known + 3600_000, created)
        assertTrue(mb.add(cmd("a", listOf("00000001", "00000002"), 100), 100))
        assertFalse("duplicate id", mb.add(cmd("a", listOf("00000001"), 100), 100))
        assertTrue(mb.add(cmd("b", listOf("00000003"), 200, created = 5), 200))
        assertEquals(3, mb.tagCount)
        // the command seen first under the tag wins (not the newest created_at), only if known before the report
        assertTrue(mb.add(cmd("c", listOf("00000003"), 300, created = 9), 300))
        assertEquals("cap evicts the oldest", 2, mb.size)
        assertNull(mb.match("00000001", 1000, 1000, setOf("")).firstOrNull())
        assertEquals("b", mb.match("00000003", 250, 1000, setOf("")).firstOrNull()!!.id)
        assertEquals("seen first", "b", mb.match("00000003", 301, 1000, setOf("")).firstOrNull()!!.id)
        assertEquals("route filter", "c", mb.match("00000003", 301, 1000, setOf("")) { it.id != "b" }.first().id)
        mb.take(mb.match("00000003", 301, 1000, setOf("")).firstOrNull()!!, 1000)
        assertNull("one downlink per x", mb.match("00000003", 2000, 2000, setOf("")).firstOrNull())
        assertFalse("tag already handled", mb.add(cmd("d", listOf("00000003"), 2100), 2100))
        assertEquals(0, mb.size)
    }

    @Test
    fun commandHeardOnAirIsNotRepeated() = runTest {
        val (b, m, _) = bridge()
        b.onRelayEvent(commandEvent().toJson())
        b.hear(pc) // another bridge sent it
        now += 1000
        b.hear(p)
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
    }

    // ------------------------------------------------------------------ election

    @Test
    fun electionFewestHopsThenHash() {
        val a = Election.Claim(testPub(1).hex(), 0)
        val b = Election.Claim(testPub(2).hex(), 1)
        assertTrue(Election.wins(a, listOf(b), y))
        assertFalse(Election.wins(b, listOf(a), y))
        val c = Election.Claim(testPub(3).hex(), 0)
        val ha = sha256(testPub(1) + y.unhex()!!).hex()
        val hc = sha256(testPub(3) + y.unhex()!!).hex()
        assertEquals(ha < hc, Election.wins(a, listOf(c), y))
        assertEquals(hc < ha, Election.wins(c, listOf(a), y))
        assertTrue("alone", Election.wins(a, emptyList(), y))
        assertTrue("own claim echoed back", Election.wins(a, listOf(a), y))
    }

    @Test
    fun bridgeLosesElectionToCloserBridge() = runTest {
        // only claims that count (here: a configured peer, PROTOCOL.md 4.1) can win
        val (b, m, _) = bridge(cfg = BridgeConfig(peers = listOf(testPub(88).hex())))
        b.onRelayEvent(commandEvent().toJson())
        now += 1000
        b.hear(p, RxMeta(hops = 1, path = "ab".unhex(), pathHashSize = 1))
        // another bridge heard it directly and says so within the window
        b.onRelayEvent(Lt2.claimEvent(y, 0, now / 1000, testKey(88)).toJson(), "wss://a")
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(0, b.mailbox.size) // handled: lost, not retried
    }

    // ------------------------------------------------------------------ airtime and persistence

    @Test
    fun airtimeBudgetStopsTransmissions() = runTest {
        // 0.001 % of an hour = 36 ms: less than one command packet
        val (b, m, _) = bridge(cfg = BridgeConfig(ltAirtimeFraction = 0.00001))
        b.onRelayEvent(commandEvent().toJson())
        now += 1000
        b.hear(p)
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertTrue(b.activity.value.any { it.text.contains("airtime") })
    }

    @Test
    fun ledgerAndMailboxSurviveRestart() = runTest {
        val (b, _, r) = bridge()
        b.onRelayEvent(commandEvent(ys = listOf("deadbeef")).toJson())
        b.onRelayEvent(commandEvent().toJson())
        b.hear(vec("reports", 1).s("p").unhex()!!)
        advanceUntilIdle()
        assertEquals(1, r.ofKind(Lt2.KIND_REPORT).size)
        val json = Json.encodeToString(BridgeState.serializer(), b.exportState())

        val (b2, m2, r2) = bridge()
        b2.importState(Json.decodeFromString(BridgeState.serializer(), json))
        assertEquals(2, b2.mailbox.size)
        b2.hear(vec("reports", 1).s("p").unhex()!!) // already published before the restart
        now += 1000
        b2.hear(p)
        advanceUntilIdle()
        assertEquals(listOf(y), r2.ofKind(Lt2.KIND_REPORT).map { it.tags[0][1] })
        assertEquals(pc.hex(), m2.sent.single().payload.hex())
    }

    @Test
    fun subscriptions() = runTest {
        val (b, _, _) = bridge()
        val subs = b.subscriptions()
        assertEquals(setOf("nm-lt", "nm-cmd", "nm-del", "nm-ann"), subs.keys)
        val kinds = subs.values.flatten().map { it["kinds"]!!.jsonArray[0].jsonPrimitive.content.toInt() }.toSet()
        assertEquals(setOf(Lt2.KIND_REPORT, Lt2.KIND_CLAIM, Lt2.KIND_COMMAND, Lt2.KIND_DELETION, 38244), kinds)
        assertEquals("4244", subs["nm-del"]!![0]["#k"]!!.jsonArray[0].jsonPrimitive.content)
    }
}

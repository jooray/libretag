package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.Device
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.nostr.Nip04
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeTest {
    private val nowS = 1_763_020_900L
    private val pathCaps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)

    private fun TestScope.bridge(
        devices: List<Device> = emptyList(),
        downlink: Boolean = false,
        caps: Set<String> = pathCaps,
    ): Triple<Bridge, FakeMesh, FakeRelays> {
        val m = FakeMesh(caps)
        val r = FakeRelays()
        val b = Bridge(this, m, r, BridgeConfig(downlinkDm = downlink), devices, clock = { nowS * 1000 }, random = Random(1), newKey = keySource())
        return Triple(b, m, r)
    }

    /** A NIP-04-shaped kind-4 event (content need not decrypt for the bridge). */
    private fun dm(from: Int, to: ByteArray, text: String, at: Long): NostrEvent {
        val iv = sha256(text.toByteArray()).copyOf(16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), IvParameterSpec(iv))
        return NostrEvent.sign(testKey(from), at, 4, listOf(listOf("p", to.hex())), Nip04.content(c.doFinal(text.toByteArray()), iv))
    }

    /** The device's own uplink (a dm4.full frame from it to its owner): the rendezvous. */
    private fun deviceFrame(text: String, at: Long = nowS - 5) = Frames.encodeDm(dm(1, testPub(2), text, at), ref = false)

    private val tag = Device("tag", testPub(1), testPub(2), listOf(testPub(2)))

    @Test
    fun publishesRoadReportOnce() = runTest {
        val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("road-events-v1.json")!!.readText())
            .jsonObject["valid"]!!.jsonArray[0].jsonObject
        val frame = v["frame"]!!.jsonPrimitive.content.unhex()!!
        val created = v["event"]!!.jsonObject["created_at"]!!.jsonPrimitive.content.toLong()
        val m = FakeMesh()
        val r = FakeRelays()
        val b = Bridge(this, m, r, BridgeConfig(), emptyList(), clock = { (created + 60) * 1000 })
        b.onDatagram(Frames.CHANNEL_ROAD, Frames.DATA_TYPE_ROAD, frame, 2)
        b.onDatagram(Frames.CHANNEL_ROAD, Frames.DATA_TYPE_ROAD, frame, 3)
        advanceUntilIdle()
        assertEquals(1, r.published.size)
        assertEquals(v["event"]!!.jsonObject["id"], r.published[0]["id"])
    }

    @Test
    fun dmWaitsForDeviceFrameThenGoesZeroHop() = runTest {
        val (b, m, _) = bridge(listOf(tag), downlink = true)
        b.onRelayEvent(dm(2, testPub(1), "status", nowS - 10).toJson())
        b.onRelayEvent(dm(3, testPub(1), "spam", nowS - 10).toJson()) // not allowed
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(1, b.stats.value.queued)
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("hi"), RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        advanceUntilIdle()
        assertEquals(1, m.sent.size)
        assertEquals(Route.ZeroHop, m.sent[0].route)
        assertEquals(Frames.CHANNEL_NOSTR, m.sent[0].channel)
        assertEquals(0, b.stats.value.queued)
    }

    @Test
    fun replayedDeviceFrameDoesNotOpenTheQueue() = runTest {
        val (b, m, _) = bridge(listOf(tag), downlink = true)
        val frame = deviceFrame("hello")
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, frame, RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        b.onRelayEvent(dm(2, testPub(1), "status", nowS - 1).toJson())
        // the same frame again (replay, or a second flood copy): must not flush
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, frame, RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(1, b.stats.value.queued)
    }

    @Test
    fun staleDeviceFrameDoesNotOpenTheQueue() = runTest {
        val (b, m, _) = bridge(listOf(tag), downlink = true)
        b.onRelayEvent(dm(2, testPub(1), "status", nowS - 1).toJson())
        // signed 2 minutes ago: past the 60 s RX window
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("old", nowS - 120), RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        // fresh, but the server heard it long ago (replay from the cursor)
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("replay"),
            RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1, receivedAtMs = nowS * 1000 - 60_000))
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
    }

    @Test
    fun dmNeverFloods() = runTest {
        // far away device: stays queued
        val (b, m, _) = bridge(listOf(tag), downlink = true)
        b.onRelayEvent(dm(2, testPub(1), "status", nowS - 1).toJson())
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("far"), RxMeta(hops = 5, path = ByteArray(5), pathHashSize = 1))
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(1, b.stats.value.queued)
        // server without tx_path_data: zero-hop is impossible, nothing is sent
        val (b2, m2, _) = bridge(listOf(tag), downlink = true, caps = emptySet())
        b2.onRelayEvent(dm(2, testPub(1), "status", nowS - 1).toJson())
        b2.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("near"), RxMeta(hops = 0))
        advanceUntilIdle()
        assertEquals(0, m2.sent.size)
    }

    @Test
    fun dmDirectAlongReversedPath() = runTest {
        val (b, m, _) = bridge(listOf(tag), downlink = true)
        b.onRelayEvent(dm(2, testPub(1), "status", nowS - 1).toJson())
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("2 hops"),
            RxMeta(hops = 2, path = "a1a2b1b2".unhex(), pathHashSize = 2, routeType = "flood"))
        advanceUntilIdle()
        val r = m.sent.single().route as Route.Direct
        assertEquals("b1b2a1a2", r.path.hex())
        assertEquals(2, r.hashSize)
    }

    @Test
    fun dmTooLongForOnePacketIsDropped() = runTest {
        val (b, m, _) = bridge(listOf(tag), downlink = true)
        b.onRelayEvent(dm(2, testPub(1), "x".repeat(120), nowS - 1).toJson())
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("hi"), RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(0, b.stats.value.queued)
    }

    @Test
    fun dmToAwakeDeviceUsesItsLastRoute() = runTest {
        val dev = Device("boiler", testPub(1), testPub(2), listOf(testPub(2)), sleeps = false)
        val (b, m, _) = bridge(listOf(dev), downlink = true)
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, deviceFrame("up"), RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        val e = dm(2, testPub(1), "on", nowS - 5)
        b.onRelayEvent(e.toJson())
        b.onRelayEvent(e.toJson()) // second relay
        advanceUntilIdle()
        assertEquals(1, m.sent.size)
        assertEquals(Route.ZeroHop, m.sent[0].route)
        val keys = listOf(testPub(1), testPub(2))
        assertEquals(e, Frames.decode(m.sent[0].payload, resolve = { r -> keys.filter { it.copyOf(4).contentEquals(r) } }).event)
    }

    @Test
    fun downlinkOffByDefaultAndUnregisteredDmNotPublished() = runTest {
        val (b, m, r) = bridge()
        b.onRelayEvent(dm(2, testPub(1), "x", nowS).toJson())
        val frame = Frames.encodeDm(dm(5, testPub(6), "hi", nowS), ref = false)
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, frame, 0)
        advanceUntilIdle()
        assertEquals(0, m.sent.size)
        assertEquals(0, r.published.size)
        assertEquals(1, b.stats.value.dropped)
    }

    @Test
    fun rateLimitsFollowConfig() = runTest {
        val (b, _, r) = bridge()
        b.config = b.config.copy(rxPerMinute = 1, uplinkProfiles = setOf("dm4.full"), unregisteredDm = true)
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, Frames.encodeDm(dm(5, testPub(6), "a", nowS), ref = false), 0)
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, Frames.encodeDm(dm(5, testPub(6), "b", nowS), ref = false), 0)
        advanceUntilIdle()
        assertEquals(1, r.published.size)
        assertTrue(b.activity.value.any { it.text.contains("príliš veľa") })
    }
}

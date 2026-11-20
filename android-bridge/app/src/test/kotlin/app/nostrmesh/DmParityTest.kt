package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.Device
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.frames.Frames
import app.nostrmesh.nostr.Nip04
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.relay.InboxRelays
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** dm4x frames, unregistered_expiring_dm and DMs to the recipient's inbox relays (parity with bridge.py, inbox.py). */
@OptIn(ExperimentalCoroutinesApi::class)
class DmParityTest {
    private val nowS = 1_763_020_900L
    private val pathCaps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)

    private fun TestScope.bridge(cfg: BridgeConfig = BridgeConfig(), devices: List<Device> = emptyList(), clockS: Long = nowS) =
        FakeMesh(pathCaps).let { m ->
            val r = FakeRelays()
            Triple(Bridge(this, m, r, cfg, devices, clock = { clockS * 1000 }, random = Random(1), newKey = keySource()), m, r)
        }

    private fun dm(from: Int, to: ByteArray, text: String, at: Long, expiring: Boolean): NostrEvent {
        val iv = sha256(text.toByteArray()).copyOf(16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), IvParameterSpec(iv))
        return NostrEvent.sign(testKey(from), at, 4, Frames.dm4Tags(to.hex(), at, expiring),
            Nip04.content(c.doFinal(text.toByteArray()), iv))
    }

    private suspend fun Bridge.hear(frame: ByteArray) =
        onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR, frame, RxMeta(hops = 1))

    // ------------------------------------------------------------------ dm4x

    @Test
    fun expiringDmFromUnknownKeysGoesUpPlainDmDoesNot() = runTest {
        val (b, _, r) = bridge()
        val x = dm(5, testPub(6), "batt 9%", nowS - 5, expiring = true)
        val plain = dm(5, testPub(6), "hello", nowS - 4, expiring = false)
        b.hear(Frames.encodeDm(x, Frames.DM4X_FULL))
        b.hear(Frames.encodeDm(plain, Frames.DM4_FULL))
        advanceUntilIdle()
        val up = r.ofKind(4)
        assertEquals(listOf(x.id), up.map { it.id })
        assertEquals(listOf("expiration", (x.createdAt + 14 * 86400).toString()), up[0].tags[1])
        assertTrue(b.activity.value.any { it.profile == "dm4.full" && it.text.contains("registra") })
    }

    @Test
    fun expiringDmSwitchesFollowTheDm4Twin() = runTest {
        val x = Frames.encodeDm(dm(5, testPub(6), "batt 9%", nowS - 5, expiring = true), Frames.DM4X_FULL)
        // unregistered_expiring_dm off: same rule as dm4
        val (b1, _, r1) = bridge(BridgeConfig(unregisteredExpiringDm = false))
        b1.hear(x)
        // the dm4.full switch also covers dm4x.full
        val (b2, _, r2) = bridge(BridgeConfig(uplinkProfiles = setOf("dm4.ref")))
        b2.hear(x)
        // dm4 max age (7 d) applies to dm4x
        val (b3, _, r3) = bridge(clockS = nowS + 8 * 86400)
        b3.hear(x)
        advanceUntilIdle()
        assertEquals(0, r1.published.size + r2.published.size + r3.published.size)
    }

    @Test
    fun registeredExpiringRefFrameDecodesAndGoesUp() = runTest {
        val tag = Device("tag", testPub(1), testPub(2), listOf(testPub(2)))
        val (b, _, r) = bridge(devices = listOf(tag))
        val x = dm(1, testPub(2), "low battery", nowS - 5, expiring = true)
        b.hear(Frames.encodeDm(x, ref = true).also { assertEquals(Frames.DM4X_REF, it[0].toInt()) })
        advanceUntilIdle()
        assertEquals(x.id, r.ofKind(4).single().id)
    }

    @Test
    fun relayDmWithExpirationGoesDownAsDm4xRef() = runTest {
        val tag = Device("tag", testPub(1), testPub(2), listOf(testPub(2)), sleeps = false)
        val (b, m, _) = bridge(BridgeConfig(downlinkDm = true), devices = listOf(tag))
        // the device spoke directly: the way back is zero-hop
        b.onDatagram(Frames.CHANNEL_NOSTR, Frames.DATA_TYPE_NOSTR,
            Frames.encodeDm(dm(1, testPub(2), "up", nowS - 2, expiring = false), ref = false),
            RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1))
        b.onRelayEvent(dm(2, testPub(1), "reply", nowS - 1, expiring = true).toJson())
        // an expiration other than +14 d has no frame: ignored
        val odd = NostrEvent.sign(testKey(2), nowS - 1, 4, listOf(listOf("p", testPub(1).hex()), listOf("expiration", "${nowS + 60}")),
            dm(2, testPub(1), "x", nowS - 1, false).content)
        b.onRelayEvent(odd.toJson())
        advanceUntilIdle()
        val s = m.sent.single()
        assertEquals(Frames.DM4X_REF, s.payload[0].toInt())
    }

    // ------------------------------------------------------------------ inbox relays

    @Test
    fun dmAlsoGoesToRecipientInboxRelaysCached() = runTest {
        val (b, _, r) = bridge()
        val rcpt = testKey(6)
        r.stored += NostrEvent.sign(rcpt, nowS - 100, 10050, listOf(
            listOf("relay", "wss://inbox.example.org/"),
            listOf("relay", "ws://plain.example.org"),
            listOf("relay", "wss://192.168.1.5"),
            listOf("relay", "wss://relay.local"),
            listOf("relay", "wss://nostr.example.com"), // already in the bridge's own set
            listOf("relay", "wss://10.0.0.1:7777"),
        ), "").toJson()
        r.stored += NostrEvent.sign(rcpt, nowS - 100, 10002, listOf(
            listOf("r", "wss://read.example.net", "read"),
            listOf("r", "wss://write.example.net", "write"),
            listOf("r", "wss://both.example.net"),
        ), "").toJson()
        // somebody else's list for this pubkey is not taken
        r.stored += NostrEvent.sign(testKey(9), nowS, 10050, listOf(listOf("relay", "wss://evil.example.org")), "").toJson()
        b.hear(Frames.encodeDm(dm(5, testPub(6), "batt 9%", nowS - 5, expiring = true), Frames.DM4X_FULL))
        b.hear(Frames.encodeDm(dm(5, testPub(6), "batt 8%", nowS - 3, expiring = true), Frames.DM4X_FULL))
        advanceUntilIdle()
        assertEquals(2, r.ofKind(4).size)
        assertEquals(2, r.publishedOnce.size)
        assertEquals(listOf("wss://inbox.example.org", "wss://read.example.net", "wss://both.example.net"), r.publishedOnce[0].second)
        assertEquals(r.ofKind(4).map { it.id }.toSet(), r.publishedOnce.map { it.first["id"]!!.jsonPrimitive.content }.toSet())
        assertEquals("one lookup per recipient (6 h cache)", 1, r.queries)
    }

    @Test
    fun inboxRelaysOff() = runTest {
        val (b, _, r) = bridge(BridgeConfig(dmInboxRelays = false))
        r.stored += NostrEvent.sign(testKey(6), nowS, 10050, listOf(listOf("relay", "wss://inbox.example.org")), "").toJson()
        b.hear(Frames.encodeDm(dm(5, testPub(6), "batt", nowS - 5, expiring = true), Frames.DM4X_FULL))
        advanceUntilIdle()
        assertEquals(0, r.queries)
        assertEquals(0, r.publishedOnce.size)
    }

    @Test
    fun safeUrlRules() {
        assertEquals("wss://relay.example.com", InboxRelays.safeUrl("wss://Relay.Example.com/"))
        assertEquals("wss://relay.example.com:4443/inbox", InboxRelays.safeUrl(" wss://relay.example.com:4443/inbox/ "))
        assertEquals("wss://8.8.8.8", InboxRelays.safeUrl("wss://8.8.8.8"))
        for (bad in listOf(
            "ws://relay.example.com", "https://relay.example.com", "wss://localhost", "wss://relay.lan", "wss://box.home.arpa",
            "wss://intranet", "wss://127.0.0.1", "wss://10.1.2.3", "wss://172.16.0.1", "wss://192.168.0.10", "wss://100.64.1.1",
            "wss://169.254.1.1", "wss://0.0.0.0", "wss://[::1]", "wss://[fd00::1]", "wss://[fe80::1]", "wss://user:pw@relay.example.com",
            "wss://", "not a url", "wss://" + "a".repeat(260) + ".com",
        )) assertNull(bad, InboxRelays.safeUrl(bad))
    }

    @Test
    fun parseNewestListAndExtraLimit() {
        val pk = testPub(6).hex()
        val old = NostrEvent.sign(testKey(6), 100, 10050, listOf(listOf("relay", "wss://old.example.org")), "")
        val new = NostrEvent.sign(testKey(6), 200, 10050, (1..9).map { listOf("relay", "wss://r$it.example.org") }, "")
        val urls = InboxRelays.parse(listOf(old, new), pk)
        assertEquals(9, urls.size)
        assertTrue("wss://old.example.org" !in urls)
        assertEquals(6, InboxRelays.extra(urls, listOf("wss://r1.example.org/")).size)
        assertEquals("wss://r2.example.org", InboxRelays.extra(urls, listOf("wss://r1.example.org/")).first())
    }

    @Test
    fun cacheKeepsHitsSixHoursMissesTenMinutes() {
        val c = InboxRelays.Cache()
        c.put("a", listOf("wss://x.example.org"), 0)
        c.put("b", emptyList(), 0)
        assertEquals(listOf("wss://x.example.org"), c.get("a", 6 * 3600_000L - 1))
        assertNull(c.get("a", 6 * 3600_000L))
        assertEquals(emptyList<String>(), c.get("b", 599_999))
        assertNull(c.get("b", 600_000))
    }
}

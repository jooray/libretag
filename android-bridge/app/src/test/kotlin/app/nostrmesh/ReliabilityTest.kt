package app.nostrmesh

import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.BridgeConfig
import app.nostrmesh.bridge.BridgeState
import app.nostrmesh.bridge.Caps
import app.nostrmesh.bridge.Outbox
import app.nostrmesh.bridge.Publisher
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.bridge.Watchdog
import app.nostrmesh.frames.Frames
import app.nostrmesh.libretag.Lt2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Outbox, watchdog and heartbeat (parity with the host bridge: bridge.py, ledger.py, test_mailbox_limits.py). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityTest {
    private val t0 = 1_763_020_900_000L
    private val caps = setOf(Caps.TX_PATH_DATA, Caps.RX_PATH)
    private val direct0 = RxMeta(hops = 0, path = ByteArray(0), pathHashSize = 1)

    /** Relays that take nothing until [up]. */
    private class FlakyRelays(var up: Boolean = false) : Publisher {
        val published = mutableListOf<JsonObject>()
        var attempts = 0
        override suspend fun publish(event: JsonObject): Boolean {
            attempts++
            if (up) published += event
            return up
        }
        override fun subscribe(subId: String, filters: List<JsonObject>) {}
    }

    private fun TestScope.bridge(r: Publisher, cfg: BridgeConfig = BridgeConfig().legacyTiming()) =
        Bridge(this, FakeMesh(caps), r, cfg, emptyList(), clock = { t0 + testScheduler.currentTime },
            random = Random(3), newKey = keySource())

    private fun report(i: Int) = ByteArray(Lt2.REPORT_LEN) { (it * 7 + i).toByte() }.also { it[0] = i.toByte(); it[1] = (i shr 8).toByte() }

    private suspend fun Bridge.hear(p: ByteArray, meta: RxMeta = direct0) =
        onDatagram(Frames.CHANNEL_LIBRETAG, Frames.DATA_TYPE_LIBRETAG, p, meta)

    private fun roundTrip(s: BridgeState) =
        Json.decodeFromString(BridgeState.serializer(), Json.encodeToString(BridgeState.serializer(), s))

    @Test
    fun failedPublishIsKeptAcrossRestartAndPublishedOnceWhenARelayReturns() = runTest {
        val relays = FlakyRelays()
        val b = bridge(relays)
        b.hear(report(1))
        advanceUntilIdle()
        assertEquals(1, relays.attempts)
        assertEquals(1, b.outboxSize)
        assertEquals(1, b.stats.value.outbox)

        // the process dies; a new one restores the saved state
        val b2 = bridge(relays)
        b2.importState(roundTrip(b.exportState()))
        assertEquals(1, b2.outboxSize)

        relays.up = true
        assertEquals(0, b2.retryOutbox()) // first retry only after 15 min
        advanceTimeBy(Outbox.BACKOFF_MS[0] + 1)
        assertEquals(1, b2.retryOutbox())
        advanceTimeBy(Outbox.BACKOFF_MS.last() * 2)
        assertEquals(0, b2.retryOutbox())
        assertEquals(1, relays.published.size)
        assertEquals(Lt2.KIND_REPORT, relays.published[0]["kind"]!!.jsonPrimitive.long.toInt())
        assertEquals(0, b2.outboxSize)
        val st = b2.status()
        assertEquals(1L, st["counters"]!!.jsonObject["outbox_published"]!!.jsonPrimitive.long)
        assertEquals(0L, st["outbox"]!!.jsonPrimitive.long)
    }

    @Test
    fun retriesFollowTheBackoffAndOldStateFilesStillLoad() = runTest {
        val relays = FlakyRelays()
        val b = bridge(relays)
        b.hear(report(2))
        advanceUntilIdle()
        // 15, 15, 30, 60, 60 min after the first attempt
        for (wait in listOf(900_000L, 900_000L, 1_800_000L, 3_600_000L, 3_600_000L)) {
            val before = relays.attempts
            advanceTimeBy(wait - 1_000)
            b.retryOutbox()
            assertEquals(before, relays.attempts)
            advanceTimeBy(1_001)
            b.retryOutbox()
            assertEquals(before + 1, relays.attempts)
        }
        // a state file written by 0.3.1 (no outbox field) loads with an empty outbox
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(BridgeState.serializer(), """{"v":1,"ledger":[],"lastCommandAt":5}""")
        assertTrue(old.outbox.isEmpty())
        assertEquals(5L, old.lastCommandAt)
    }

    @Test
    fun expiredEventIsDroppedAndCounted() = runTest {
        val relays = FlakyRelays()
        val b = bridge(relays, BridgeConfig(outboxTtlMs = 3_600_000L).legacyTiming())
        b.hear(report(3))
        advanceUntilIdle()
        assertEquals(1, b.outboxSize)
        advanceTimeBy(3_600_000L)
        relays.up = true
        assertEquals(0, b.retryOutbox())
        assertEquals(0, b.outboxSize)
        assertTrue(relays.published.isEmpty())
        val st = b.status()
        assertEquals(1L, st["counters"]!!.jsonObject["outbox_expired"]!!.jsonPrimitive.long)
        assertTrue(st["total"]!!.jsonObject["dropped"]!!.jsonPrimitive.double >= 1.0)
    }

    @Test
    fun eventExpirationCapsTheOutboxLifetime() {
        val ob = Outbox(ttlMs = Outbox.TTL_MS)
        val exp = ob.put("a", "{}", "k", "p", 1_000L, eventExpiresMs = 5_000L)
        assertEquals(5_000L, exp)
        assertEquals(1_000L + Outbox.TTL_MS, ob.put("b", "{}", "k", "p", 1_000L, null))
        val (due, expired) = ob.due(5_000L)
        assertTrue(due.isEmpty())
        assertEquals(listOf("a"), expired.map { it.id })
    }

    @Test
    fun watchdogReconnectsExactlyOncePerSilence() {
        val min = 60_000L
        val w = Watchdog(30 * min, startedAt = 0)
        val fired = (1..60).filter { m -> w.due(m * min, lastActivity = 0) }
        assertEquals(listOf(31), fired)
        assertEquals(1, w.reconnects)
        // adapter activity (any message from the server) counts: a quiet but live link stays up
        val live = Watchdog(30 * min, startedAt = 0)
        assertFalse((1..120).any { m -> live.due(m * min, lastActivity = (m - 5) * min) })
        assertFalse(Watchdog(0, 0).due(10 * 3600_000L, 0))
    }

    @Test
    fun heartbeatHasTheHostBridgeKeys() = runTest {
        val b = bridge(FakeRelays())
        b.hear(report(4))
        advanceUntilIdle()
        val st = b.status()
        assertEquals(HOST_STATUS_KEYS, st.keys)
        assertEquals(HOST_SUMMARY_KEYS, st["total"]!!.jsonObject.keys)
        assertEquals(HOST_SUMMARY_KEYS, st["last_hour"]!!.jsonObject.keys)
        assertEquals(setOf("main", "libretag"), st["airtime_window_s"]!!.jsonObject.keys)
        assertEquals(setOf("main", "libretag"), st["airtime_cap_s"]!!.jsonObject.keys)
        assertEquals(1.0, st["last_hour"]!!.jsonObject["published"]!!.jsonPrimitive.double, 0.0)
        assertFalse(b.heartbeatDue())
        advanceTimeBy(3_600_000L)
        assertTrue(b.heartbeatDue())
        val (hb, line) = b.heartbeat()
        assertTrue(line, line.startsWith("heartbeat heard=1 published=1 dropped=0"))
        assertEquals(1.0, hb["last_hour"]!!.jsonObject["heard"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, b.status()["last_hour"]!!.jsonObject["heard"]!!.jsonPrimitive.double, 0.0)
        assertFalse(b.heartbeatDue())
    }

    companion object {
        /** `Bridge.status` and `Bridge.summary` in host/src/nostrmesh/bridge.py (test_status_keys there). */
        val HOST_STATUS_KEYS = setOf("at", "started_at", "interval_s", "total", "last_hour", "airtime_window_s",
            "airtime_cap_s", "radio_silence_s", "mailbox_tags", "outbox", "counters")
        val HOST_SUMMARY_KEYS = setOf("heard", "published", "dropped", "spam_rejected", "sent_to_radio", "airtime_s")
    }
}

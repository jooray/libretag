package app.libretag.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxRelaysTest {
    private fun ev(kind: Int, at: Long, vararg tags: List<String>) = NostrEvent("aa", at, kind, tags.toList(), "")

    @Test fun readAndDmRelaysNewestOnlyFiltered() {
        val events = listOf(
            ev(10002, 100, listOf("r", "wss://old.example.com")),
            ev(10002, 200, listOf("r", "wss://read.example.com/"), listOf("r", "wss://both.example.com", ""),
                listOf("r", "wss://write.example.com", "write"), listOf("r", "wss://marked.example.com", "read"),
                listOf("r", "ws://plain.example.com"), listOf("r", "wss://localhost:7777"), listOf("r", "wss://relay.local"),
                listOf("r", "wss://192.168.1.5"), listOf("r", "wss://10.0.0.2"), listOf("r", "wss://[::1]")),
            ev(10050, 150, listOf("relay", "wss://inbox.example.com"), listOf("relay", "wss://read.example.com")),
        )
        assertEquals(listOf("wss://inbox.example.com", "wss://read.example.com", "wss://both.example.com", "wss://marked.example.com"),
            InboxRelays.parse(events))
    }

    @Test fun dmTargetsOursThenInboxNormalized() {
        val ours = listOf("wss://nostr.cypherpunk.today", "wss://relay.primal.net", "wss://nos.lol")
        // the owner's 10050 + 10002, as parse() returns them
        val inbox = listOf("wss://relay.primal.net", "wss://nos.lol", "wss://nostr.cypherpunk.today", "wss://auth.nostr1.com", "wss://relay.damus.io")
        assertEquals(ours + listOf("wss://auth.nostr1.com", "wss://relay.damus.io"), InboxRelays.dmTargets(ours, inbox))
        assertEquals(listOf("wss://a.example.com"), InboxRelays.dmTargets(listOf("wss://a.example.com/", "wss://a.example.com"), listOf("wss://a.example.com")))
        val many = (1..10).map { "wss://r$it.example.com" }
        assertEquals(1 + InboxRelays.MAX_EXTRA, InboxRelays.dmTargets(listOf("wss://own.example.com"), many).size)
        assertEquals(listOf("wss://x.example.com"), InboxRelays.dmTargets(emptyList(), listOf("wss://x.example.com")))
    }

    @Test fun publicHosts() {
        assertTrue(InboxRelays.isPublicWss("wss://nos.lol"))
        assertTrue(InboxRelays.isPublicWss("wss://8.8.8.8"))
        assertFalse(InboxRelays.isPublicWss("wss://172.20.0.1"))
        assertFalse(InboxRelays.isPublicWss("wss://100.64.1.1"))
        assertFalse(InboxRelays.isPublicWss("https://nos.lol"))
        assertFalse(InboxRelays.isPublicWss("wss://printer"))
        assertNull(InboxRelays.normalize("ws://x.example.com"))
    }

    @Test fun unionAddsAtMostSixNewOnes() {
        val own = listOf("wss://a.example.com", "wss://b.example.com")
        val inbox = listOf("wss://b.example.com/") + (1..9).map { "wss://x$it.example.com" }
        val u = InboxRelays.union(own, inbox)
        assertEquals(own + (1..6).map { "wss://x$it.example.com" }, u)
    }

    @Test fun lowBatteryDmExpiresInFourteenDays() {
        val sk = ByteArray(32) { (it + 1).toByte() }
        val to = NostrEvent.xonly(ByteArray(32) { (it + 2).toByte() })
        val e = TagDm.event(sk, to, "battery 18 %", 1_800_000_000, TagDm.LOW_BATTERY_TTL_S)
        assertEquals(4, e.kind)
        assertTrue(e.verify())
        assertEquals(listOf("expiration", (1_800_000_000 + 14 * 86400L).toString()), e.tags.first { it[0] == "expiration" })
        val welcome = TagDm.event(sk, to, "hello", 1_800_000_000)
        assertTrue(welcome.tags.none { it[0] == "expiration" })
    }
}

package app.libretag.nostr

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class BridgeTargetsTest {
    private val now = 1_763_200_000L
    private fun key(i: Int) = MessageDigest.getInstance("SHA-256").digest("bridge targets $i".toByteArray())
    private fun pk(i: Int) = NostrEvent.xonly(key(i)).hex()

    private fun announce(i: Int, net: String = "sk", exp: Long = now + 7 * 86400) =
        NostrEvent.sign(key(i), now - 100, BridgeTargets.ANNOUNCE_KIND,
            listOf(listOf("d", net), listOf("k", "4244"), listOf("signed", "60", "60"), listOf("open", "1"),
                listOf("expiration", exp.toString())), "{}")

    private fun receipt(i: Int, y: String, hops: String = "1-3", at: Long = now - 600) =
        NostrEvent.sign(key(i), at, BridgeTargets.RECEIPT_KIND,
            listOf(listOf("y", y), listOf("hops", hops), listOf("expiration", (at + 48 * 3600).toString())), "")

    private fun ann(i: Int, net: String = "sk") = BridgeTargets.parseAnnouncement(announce(i, net), now)!!
    private fun rc(i: Int, y: String, hops: String = "1-3", at: Long = now - 600) = BridgeTargets.parseReceipt(receipt(i, y, hops, at), now)!!

    private fun y(n: Int, j: Int = 0) = "%02x%02x0000".format(n, j)
    private val recent = (0..9).flatMap { n -> (0..1).map { j -> y(n, j) to n.toLong() } }.toMap()

    @Test fun parseReceipt() {
        val r = rc(1, y(3), "0")
        assertEquals(pk(1), r.pubkey); assertEquals(y(3), r.y); assertEquals("0", r.hops)
        assertNull(BridgeTargets.parseReceipt(receipt(1, y(3)).copy(sig = "00".repeat(64)), now))
        assertNull(BridgeTargets.parseReceipt(receipt(1, y(3), at = now - 49 * 3600), now))
        assertNull(BridgeTargets.parseReceipt(receipt(1, y(3)).copy(kind = 4244), now, verify = false))
        assertNull(BridgeTargets.parseReceipt(NostrEvent.sign(key(1), now, 4245, listOf(listOf("hops", "0")), ""), now))
        // an unknown hops class counts as "?"
        assertEquals("?", BridgeTargets.parseReceipt(receipt(1, y(3), hops = "7"), now)!!.hops)
    }

    @Test fun parseAnnouncement() {
        val a = ann(1)
        assertEquals("sk", a.network); assertTrue(a.open)
        assertNull(BridgeTargets.parseAnnouncement(announce(1, exp = now - 1), now))
        assertNull(BridgeTargets.parseAnnouncement(announce(1, net = "SK"), now))
        assertEquals("", BridgeTargets.parseAnnouncement(announce(1, net = ""), now)!!.network)
        assertNull(BridgeTargets.parseAnnouncement(announce(1).copy(content = "x"), now))
        val noK = NostrEvent.sign(key(1), now, 38244, listOf(listOf("d", "sk"), listOf("expiration", (now + 99).toString())), "")
        assertNull(BridgeTargets.parseAnnouncement(noK, now))
    }

    @Test fun onlyAnnouncedBridges() {
        val t = BridgeTargets.untimed(listOf(rc(1, y(9)), rc(2, y(9))), listOf(ann(2)), recent, now)
        assertEquals(listOf(pk(2)), t.p)
        assertEquals("sk", t.r)
    }

    @Test fun noneHeardIsOpen() {
        val t = BridgeTargets.untimed(listOf(rc(1, "ffff0000")), listOf(ann(1)), recent, now)
        assertTrue(t.open); assertNull(t.r)
        assertTrue(BridgeTargets.untimed(emptyList(), emptyList(), recent, now).open)
    }

    @Test fun ranking() {
        val receipts = listOf(
            // 1: two reports (copies j=0 and j=1 of report 5 count once), far
            rc(1, y(5, 0), "4+"), rc(1, y(5, 1), "4+"), rc(1, y(6), "4+"),
            // 2: one report, direct
            rc(2, y(7), "0"),
            // 3 and 4: one report each, same hops; 4 heard more recently
            rc(3, y(8), "1-3", at = now - 900), rc(4, y(8), "1-3", at = now - 100),
            // 5: one report, unknown hops
            rc(5, y(9), "?"),
        )
        val t = BridgeTargets.untimed(receipts, (1..5).map { ann(it) }, recent, now)
        assertEquals(listOf(pk(1), pk(2), pk(4)), t.p)
    }

    @Test fun network() {
        val rs = listOf(rc(1, y(9)), rc(2, y(9)))
        assertEquals("sk", BridgeTargets.untimed(rs, listOf(ann(1), ann(2)), recent, now).r)
        assertNull(BridgeTargets.untimed(rs, listOf(ann(1), ann(2, "cz")), recent, now).r)
        assertNull(BridgeTargets.untimed(rs, listOf(ann(1), ann(2, "")), recent, now).r)
        // a bridge that serves two networks: the common one
        assertEquals("sk", BridgeTargets.untimed(rs, listOf(ann(1), ann(2), ann(2, "cz")), recent, now).r)
    }

    @Test fun timedExactVia() {
        val rs = listOf(rc(1, y(9, 0)), rc(2, y(9, 1)), rc(3, y(8, 0)))
        val anns = (1..3).map { ann(it) }
        assertEquals(listOf(pk(1)), BridgeTargets.timed(rs, anns, y(9, 0), now).p)
        assertTrue(BridgeTargets.timed(rs, anns, "abcdef01", now).open)
    }

    @Test fun entryTags() {
        assertEquals(listOf(listOf("v", "2"), listOf("route", "flood")), BridgeTargets.entryTags(Targets.OPEN))
        val tags = BridgeTargets.entryTags(Targets(listOf(pk(1), pk(2)), "sk"))
        assertEquals(listOf(listOf("v", "2"), listOf("route", "flood"), listOf("p", pk(1)), listOf("p", pk(2)), listOf("r", "sk")), tags)
        val parsed = BridgeTargets.targeting(tags)!!
        assertEquals(2, parsed.format); assertEquals(listOf(pk(1), pk(2)), parsed.p); assertEquals("sk", parsed.r)
        assertNull(BridgeTargets.targeting(listOf(listOf("route", "flood"))))  // format 1 is gone
        assertEquals(listOf(listOf("p", pk(1)), listOf("p", pk(2)), listOf("r", "sk")),
            BridgeTargets.targetTags(Targets(listOf(pk(1), pk(2)), "sk")))
    }

    @Test fun recentYs() {
        val m = BridgeTargets.recentYs(12, 20, { n, j -> "$n.$j" })
        assertEquals((3L..12L).toList() + 20L, m.values.distinct())
        assertEquals(22, m.size)
        assertTrue(BridgeTargets.recentYs(-1, null, { n, j -> "$n.$j" }).isEmpty())
    }

    // ------------------------------------------------------------ BridgeLookup with fake relays

    private class FakeRelays(val events: List<NostrEvent>, val fail: Boolean = false) {
        val asked = mutableListOf<JsonObject>()
        suspend fun query(f: List<JsonObject>, ms: Long): List<JsonObject> {
            if (fail) error("offline $ms")
            asked += f
            val flt = f.single()
            val kinds = flt["kinds"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
            val ys = flt["#y"]?.jsonArray?.map { it.jsonPrimitive.content }
            val authors = flt["authors"]?.jsonArray?.map { it.jsonPrimitive.content }
            return events.filter { e ->
                e.kind in kinds && (ys == null || e.tags.any { it[0] == "y" && it[1] in ys }) && (authors == null || e.pubkey in authors)
            }.map { it.toJson() }
        }
    }

    @Test fun lookupView() = runBlocking {
        val relays = FakeRelays(listOf(receipt(1, y(9)), receipt(2, y(9)), announce(1), announce(3)))
        val l = BridgeLookup(relays::query) { now }
        val view = l.view(recent)
        assertEquals(listOf(pk(1)), view.untimed(now).p)
        assertEquals(2, relays.asked.size)
        // bridge 1's announcement is cached: its claim needs no query
        assertTrue(l.isBridge(pk(1)))
        assertEquals(2, relays.asked.size)
        // an unknown key is asked about once, then remembered
        assertFalse(l.isBridge(pk(2)))
        assertFalse(l.isBridge(pk(2)))
        assertEquals(3, relays.asked.size)
        // announced, never seen in a receipt
        assertTrue(l.isBridge(pk(3)))
    }

    @Test fun lookupFailureIsOpen() = runBlocking {
        val l = BridgeLookup(FakeRelays(emptyList(), fail = true)::query) { now }
        val view = l.view(recent)
        assertTrue(view.untimed(now).open)
        assertTrue(view.timed(y(9), now).open)
        assertFalse(l.isBridge(pk(1)))
    }
}

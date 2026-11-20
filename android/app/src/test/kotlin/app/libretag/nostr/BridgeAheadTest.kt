package app.libretag.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/design/tag-diagnosis.md 4.4: receipts for counters the app has not opened. */
class BridgeAheadTest {
    private val nowS = 1_800_000_000L
    private val yOf = { n: Long, j: Int -> "%08x%02x".format(n, j) }

    @Test fun candidateCounters() {
        val ys = BridgeTargets.nextYs(100, yOf)
        assertEquals(setOf(101L, 102L, 103L, 104L, 112L, 128L), ys.values.toSet())
        assertEquals(12, ys.size)
        // on a checkpoint: the next two multiples of 16, and the epoch
        val at = BridgeTargets.nextYs(127, yOf).values.toSet()
        assertEquals(setOf(128L, 129L, 130L, 131L, 144L), at)
        assertEquals(emptyMap<String, Long>(), BridgeTargets.nextYs(-1, yOf))
        assertEquals(true, BridgeTargets.nextYs(5, yOf).size <= 14)
    }

    private val bridge = "b".repeat(64)
    private val ann = Announcement(bridge, "", open = true, receipts = true, expires = nowS + 3600, relays = listOf("wss://relay.example"))

    @Test fun receiptFromAnUnannouncedKeyIgnored() {
        val ys = BridgeTargets.nextYs(100, yOf)
        val r = Receipt("c".repeat(64), yOf(101, 0), "1-3", nowS - 600, nowS + 3600)
        assertNull(BridgeTargets.ahead(listOf(r), listOf(ann), ys, nowS))
    }

    @Test fun newestReceiptWins() {
        val ys = BridgeTargets.nextYs(100, yOf)
        val a = Receipt(bridge, yOf(101, 0), "0", nowS - 3600, nowS + 3600)
        val b = Receipt(bridge, yOf(102, 1), "1-3", nowS - 600, nowS + 3600)
        val hit = BridgeTargets.ahead(listOf(a, b), listOf(ann), ys, nowS)!!
        assertEquals(102L, hit.n)
        assertEquals(listOf("wss://relay.example"), hit.announcement.relays)
    }

    @Test fun receiptDays() {
        val ys = setOf("y1", "y2", "y3")
        val day = 86_400L
        val rs = listOf(Receipt(bridge, "y1", "0", nowS - 2 * day + 10, nowS), Receipt(bridge, "y2", "0", nowS - 2 * day + 20, nowS),
            Receipt(bridge, "y3", "0", nowS, nowS), Receipt("c".repeat(64), "y1", "0", nowS - day, nowS))
        assertEquals(2, BridgeTargets.receiptDays(rs, bridge, ys))
        assertEquals(1, BridgeTargets.receiptDays(rs.take(2), bridge, ys))
    }

    @Test fun announcementRelays() {
        val ev = NostrEvent(pubkey = bridge, createdAt = nowS, kind = BridgeTargets.ANNOUNCE_KIND,
            tags = listOf(listOf("d", ""), listOf("k", "4244"), listOf("expiration", "${nowS + 3600}"),
                listOf("relay", "wss://a.example"), listOf("relay", "https://bad"), listOf("relay", "wss://a.example")),
            content = "", id = "", sig = "")
        assertEquals(listOf("wss://a.example"), BridgeTargets.parseAnnouncement(ev, nowS, verify = false)!!.relays)
    }
}

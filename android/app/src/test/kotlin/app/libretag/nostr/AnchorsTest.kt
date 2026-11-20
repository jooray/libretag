package app.libretag.nostr

import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Anchors and signed entries (PROTOCOL.md 4.2). */
class AnchorsTest {
    private val keys = Keys(ByteArray(32) { it.toByte() })

    @Test fun wantedNextEightAndTheNextEpoch() {
        assertEquals((101L..108L).toList() + 128L, AnchorPlan.wanted(100))
        // the next epoch's start already among the eight: not twice
        assertEquals((60L..67L).toList(), AnchorPlan.wanted(59))
        assertEquals((0L..7L).toList() + 64L, AnchorPlan.wanted(-1))
    }

    @Test fun ttlIsThreeIntervalsWithinBounds() {
        assertEquals(3 * 3600L, AnchorPlan.ttlS(3600))
        assertEquals(AnchorPlan.TTL_MIN_S, AnchorPlan.ttlS(120))           // TRACK every 2 min
        assertEquals(AnchorPlan.TTL_MAX_S, AnchorPlan.ttlS(24 * 3600))     // a quiet tag's heartbeat
        assertEquals(AnchorPlan.TTL_UNKNOWN_S, AnchorPlan.ttlS(null))
    }

    @Test fun dueWhenMissingOrPastHalfItsLife() {
        val now = 1_000_000L
        val ttl = 6 * 3600L
        val published = mapOf(101L to now + ttl, 102L to now + ttl / 2 - 1, 103L to now + ttl / 2 + 1)
        assertEquals(listOf(102L, 104L), AnchorPlan.due(listOf(101L, 102L, 103L, 104L), published, now, ttl))
    }

    @Test fun anchorIsSignedWithSkNAndCarriesTheTags() {
        val n = 205L
        val e = LibreTag.epoch(n)
        val targets = Targets(listOf("ab".repeat(32)), "sk")
        val ev = AnchorPlan.event(keys.cmd(e), keys.view(e), n, 1_763_200_000, 1_763_210_000, targets)
        assertTrue(ev.verify())
        assertEquals(LibreTag.mailboxPk(keys.cmd(e), n).hex(), ev.pubkey)
        assertEquals("", ev.content)
        assertEquals(listOf(listOf("v", "2"),
            listOf("y", LibreTag.bytesToHex(LibreTag.reportTag(keys.view(e), n, 0))),
            listOf("y", LibreTag.bytesToHex(LibreTag.reportTag(keys.view(e), n, 1))),
            listOf("expiration", "1763210000"), listOf("p", "ab".repeat(32)), listOf("r", "sk")), ev.tags)
        // and the report n the tag seals commits to exactly this key
        val p = keys.sealReport(n, 0, ByteArray(33).also { it[0] = 0x21 })
        assertTrue(LibreTag.commitMatches(NostrEvent.xonly(LibreTag.mailboxSk(keys.cmd(e), n)), p))
        assertTrue(!LibreTag.commitMatches(LibreTag.mailboxPk(keys.cmd(e), n + 1), p))
    }

    @Test fun bookRenewsAndForgets() {
        val f = File.createTempFile("anchors", ".json").also { it.delete() }
        val b = AnchorBook(f)
        b.record("t", 5, 2_000, 1_000)
        b.record("t", 6, 3_000, 1_000)
        b.record("t", 5, 4_000, 1_000)
        assertEquals(mapOf(5L to 4_000L, 6L to 3_000L), AnchorBook(f).published("t", 1_000))
        assertEquals(mapOf(5L to 4_000L), b.published("t", 3_000))
        b.forget("t")
        assertTrue(AnchorBook(f).published("t", 0).isEmpty())
        f.delete()
    }

    @Test fun deletionWithTheDerivedKey() {
        val n = 70L
        val sk = LibreTag.mailboxSk(keys.cmd(LibreTag.epoch(n)), n)
        val k = MailboxKey("t", 1, "ab".repeat(32), "", 5_000, n = n)
        val del = MailboxKeys.deletion(k, 2_000, 4244, sk)!!
        assertTrue(del.verify())
        assertEquals(NostrEvent.xonly(sk).hex(), del.pubkey)
        assertEquals(null, MailboxKeys.deletion(k, 2_000, 4244, null)) // no key: nothing to sign with
    }
}

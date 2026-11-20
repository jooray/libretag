package app.libretag.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxPlanTest {
    private data class E(val n: Long, val at: Long?)

    @Test fun untimedFirstThenAscendingAt() {
        val es = listOf(E(5, 3000), E(6, null), E(5, 1000), E(5, 2000), E(7, null))
        assertEquals(listOf(E(6, null), E(7, null), E(5, 1000), E(5, 2000), E(5, 3000)), MailboxPlan.order(es, { it.at }, { it.n }))
    }

    @Test fun everyEntryOfACommandCarriesTheSameGroupAndNoWork() {
        val g = MailboxPlan.newGroup()
        assertTrue(isLowerHex(g, 8))
        val sk = ByteArray(32) { 4 }
        val evs = listOf(
            listOf(listOf("y", "00000001"), listOf("y", "00000002"), listOf("route", "near")) to null,
            listOf(listOf("y", "00000003"), listOf("route", "near"), listOf("at", "1800000600"), listOf("via", "00000009")) to 1_800_000_600L,
        ).map { (base, at) ->
            val tags = MailboxPlan.tags(base, (at ?: 1_800_000_000L) + 600, g)
            NostrEvent.sign(sk, 1_800_000_000L, 4244, tags, "AA==")
        }
        for (e in evs) {
            assertEquals(listOf("g", g), e.tags.single { it[0] == "g" })
            assertTrue(e.tags.none { it[0] == "nonce" })
            assertTrue(e.verify())
        }
    }

    @Test fun lateEntriesOnlyWhileTheTagListens() {
        assertTrue(MailboxPlan.lateOk(0))
        assertTrue(MailboxPlan.lateOk(74))
        assertTrue(!MailboxPlan.lateOk(75))   // 90 s window, 15 s for relays and bridges
        assertTrue(!MailboxPlan.lateOk(-1))
        // a late entry for report n stays worth publishing while n is the newest report
        assertTrue(!MailboxPlan.stale(968, timed = false, lastN = 968, late = true))
        assertTrue(MailboxPlan.stale(967, timed = false, lastN = 968, late = true))
    }

    @Test fun entriesForAPastReportAreNotPublished() {
        // untimed: waits for report n, useless once n (or a later one) is in
        assertTrue(!MailboxPlan.stale(969, timed = false, lastN = 968))
        assertTrue(MailboxPlan.stale(968, timed = false, lastN = 968))
        assertTrue(MailboxPlan.stale(936, timed = false, lastN = 968))
        // timed: the checks after report n are valid until a newer report
        assertTrue(!MailboxPlan.stale(968, timed = true, lastN = 968))
        assertTrue(MailboxPlan.stale(935, timed = true, lastN = 968))
    }

    @Test(expected = IllegalArgumentException::class) fun groupMustBeLowerHex() { MailboxPlan.tags(emptyList(), 1, "ABCDEF0123456789") }
}

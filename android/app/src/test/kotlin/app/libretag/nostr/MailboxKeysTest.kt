package app.libretag.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailboxKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sk(i: Int) = ByteArray(32) { (i + it + 1).toByte() }

    private fun mailboxEvent(i: Int, exp: Long) =
        NostrEvent.sign(sk(i), 1_000, 4244, listOf(listOf("y", "aabbccdd"), listOf("expiration", exp.toString())), "AAAA")

    @Test
    fun deletionIsSignedBySameKeyAndReferencesTheEvent() {
        val ev = mailboxEvent(1, 5_000)
        val k = MailboxKey("t1", 7, ev.id, sk(1).hex(), 5_000)
        val del = MailboxKeys.deletion(k, 2_000)!!
        assertTrue(del.verify())
        assertEquals(5, del.kind)
        assertEquals(ev.pubkey, del.pubkey)
        assertEquals(listOf(listOf("e", ev.id), listOf("k", "4244"), listOf("expiration", "5000")), del.tags)
        assertEquals("", del.content)
        assertNull(MailboxKeys.deletion(k.copy(sk = "zz"), 2_000))
    }

    @Test
    fun claimTagsMapToThePendingCommand() {
        val a = MailboxKeys(null)
        a.add(MailboxKey("t1", 7, "e1", sk(1).hex(), 5_000, ys = listOf("aabbccdd", "11223344")), 1_000)
        a.add(MailboxKey("t2", 9, "e2", sk(2).hex(), 5_000, ys = listOf("55667788")), 1_000)
        assertEquals(setOf("aabbccdd", "11223344", "55667788"), a.pendingYs(1_000))
        assertEquals("t1" to 7, a.commandFor("11223344"))
        a.cancel("t1", 7) // a cancelled command is no longer watched
        assertNull(a.commandFor("11223344"))
        assertEquals(setOf("55667788"), a.pendingYs(1_000))
        assertEquals(emptySet<String>(), a.pendingYs(5_000))
    }

    @Test
    fun keysPersistCancelAndForget() {
        val f = tmp.root.resolve("mailbox_keys.json")
        val a = MailboxKeys(f)
        val e1 = mailboxEvent(1, 5_000)
        val e2 = mailboxEvent(2, 5_000)
        val e3 = mailboxEvent(3, 1_500)
        a.add(MailboxKey("t1", 7, e1.id, sk(1).hex(), 5_000), 1_000)
        a.add(MailboxKey("t1", 7, e2.id, sk(2).hex(), 5_000), 1_000)
        a.add(MailboxKey("t2", 3, e3.id, sk(3).hex(), 1_500), 1_000)

        val b = MailboxKeys(f) // survives a restart
        assertEquals(3, b.all().size)
        val cancelled = b.cancel("t1", 7)
        assertEquals(setOf(e1.id, e2.id), cancelled.map { it.id }.toSet())
        assertTrue(cancelled.all { it.cancelled })
        assertEquals(2, MailboxKeys(f).toDelete(1_000).size)

        b.remove("t1", 7, setOf(e1.id)) // e1's deletion reached a relay, e2's did not
        assertEquals(listOf(e2.id), b.toDelete(1_000).map { it.id })
        assertEquals(emptyList<MailboxKey>(), b.toDelete(5_000)) // expired: nothing left to delete

        b.prune(2_000) // e3 expired
        assertEquals(listOf(e2.id), MailboxKeys(f).all().map { it.id })
        b.remove("t1", 7) // acknowledged
        assertEquals(0, MailboxKeys(f).all().size)
    }

    @Test
    fun entriesForOlderReportsBecomeObsolete() {
        // 2026-11-18: Track queued just before report 968 came in (the tag had rebooted: 935 -> 968)
        val f = tmp.root.resolve("mailbox_keys.json")
        val a = MailboxKeys(f)
        for (n in 936L..939L) a.add(MailboxKey("h", 1, "u$n", sk(1).hex(), 5_000, n = n), 1_000)
        a.add(MailboxKey("h", 1, "t935", sk(2).hex(), 5_000, n = 935), 1_000) // a check after report 935
        for (n in 969L..972L) a.add(MailboxKey("h", 1, "u$n", sk(3).hex(), 5_000, n = n), 1_000)
        a.add(MailboxKey("h", 1, "t968", sk(4).hex(), 5_000, n = 968), 1_000) // a check after report 968
        a.add(MailboxKey("h", 1, "old", sk(5).hex(), 5_000), 1_000)            // stored by an older app: n unknown
        a.add(MailboxKey("w", 1, "w900", sk(6).hex(), 5_000, n = 900), 1_000)  // another tag

        val gone = a.obsolete("h", 1, 968)
        assertEquals(setOf("u936", "u937", "u938", "u939", "t935"), gone.map { it.id }.toSet())
        assertTrue(gone.all { it.cancelled })
        assertEquals(gone.map { it.id }.toSet(), MailboxKeys(f).toDelete(1_000).map { it.id }.toSet())
        assertEquals(emptyList<MailboxKey>(), a.obsolete("h", 1, 968)) // once
        // the next report: the entry for 969 itself stays (a bridge may be sending it in this report's window)
        assertEquals(setOf("t968"), a.obsolete("h", 1, 969).map { it.id }.toSet())
        assertEquals(setOf("u969"), a.obsolete("h", 1, 970).map { it.id }.toSet())
        assertTrue(!a.all().first { it.id == "old" }.cancelled)
        assertTrue(!a.all().first { it.id == "w900" }.cancelled)
    }
}

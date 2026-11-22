package app.libretag.keys

import app.libretag.data.Tracker
import app.libretag.nostr.MailboxKey
import app.libretag.nostr.MailboxKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

/** Keystore wrapping and the 0.8.0 migration of plain values (a software AES key stands in for Keystore). */
class SecretsTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = AesGcmBox { key }
    private val k0 = "11".repeat(32)

    @Before fun setUp() { Secrets.clearCache(); Secrets.box = box }
    @After fun tearDown() { Secrets.box = null; Secrets.clearCache() }

    @Test fun wrapRoundTrip() {
        val w = Secrets.wrap(k0)
        assertTrue(w.startsWith(Secrets.PREFIX))
        assertFalse(w.contains(k0))
        assertEquals(k0, Secrets.unwrap(w))
        assertEquals(w, Secrets.wrap(w)) // already wrapped: unchanged
        assertEquals("", Secrets.wrap(""))
    }

    @Test fun plainLegacyValuePassesThrough() = assertEquals(k0, Secrets.unwrap(k0))

    @Test fun unreadableValueIsKeptNotEmptied() {
        val w = Secrets.wrap(k0)
        Secrets.clearCache()
        val other = AesGcmBox { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertEquals(w, Secrets.unwrap(w, other))
    }

    @Test fun trackerMigration() {
        val plain = Tracker(name = "Keys", keyHex = k0)
        assertTrue(Secrets.trackerNeedsWrap(plain))
        val stored = Secrets.sealTracker(plain)
        assertFalse(Secrets.trackerNeedsWrap(stored))
        assertNotEquals(k0, stored.keyHex)
        assertEquals("", stored.sharedViewHex)
        Secrets.clearCache()
        assertEquals(plain, Secrets.openTracker(stored))
        val shared = Tracker(name = "S", keyHex = "", sharedBy = "x", sharedViewHex = "22".repeat(32), sharedCmdHex = "33".repeat(32),
            sharedUsbHex = "44".repeat(32), sharedIdHex = "55".repeat(8))
        val s2 = Secrets.sealTracker(shared)
        assertTrue(Secrets.isWrapped(s2.sharedViewHex) && Secrets.isWrapped(s2.sharedCmdHex) && Secrets.isWrapped(s2.sharedUsbHex))
        assertFalse(Secrets.trackerNeedsWrap(s2))
        assertTrue(Secrets.trackerNeedsWrap(s2.copy(sharedUsbHex = "44".repeat(32))))
        assertEquals(shared, Secrets.openTracker(s2))
    }

    @Test fun prefsMigrationRewritesOnlyPlainValues() {
        val kv = mutableMapOf("aSk" to "44".repeat(32), "aEnc" to Secrets.wrap("55".repeat(32)), "other" to "x")
        val before = kv["aEnc"]
        val n = Secrets.migrate(listOf("aSk", "aEnc", "missing"), { kv[it] }, { k, v -> kv[k] = v })
        assertEquals(1, n)
        assertTrue(Secrets.isWrapped(kv["aSk"]))
        assertEquals(before, kv["aEnc"])
        assertEquals("44".repeat(32), Secrets.unwrap(kv["aSk"]!!))
        assertEquals(0, Secrets.migrate(listOf("aSk", "aEnc"), { kv[it] }, { k, v -> kv[k] = v }))
    }

    @Test fun noBoxNoMigration() {
        Secrets.box = null
        val kv = mutableMapOf("aSk" to "44".repeat(32))
        assertEquals(0, Secrets.migrate(listOf("aSk"), { kv[it] }, { k, v -> kv[k] = v }))
        assertEquals("44".repeat(32), kv["aSk"])
    }

    @Test fun mailboxKeysFileMigrates() {
        val f = File.createTempFile("mailbox", ".json")
        val sk = "66".repeat(32)
        f.writeText("""[{"tracker":"t","seq":1,"id":"${"ab".repeat(32)}","sk":"$sk","exp":9999999999}]""")
        val m = MailboxKeys(f)
        assertEquals(sk, m.all().single().sk)        // in memory: plain
        assertFalse(f.readText().contains(sk))       // on disk: rewritten wrapped at first load
        assertTrue(f.readText().contains(Secrets.PREFIX))
        Secrets.clearCache()
        assertEquals(sk, MailboxKeys(f).all().single().sk)
        m.add(MailboxKey("t", 2, "cd".repeat(32), "77".repeat(32), 9999999999), 0)
        assertFalse(f.readText().contains("77".repeat(32)))
        f.delete()
    }
}

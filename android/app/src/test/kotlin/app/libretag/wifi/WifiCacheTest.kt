package app.libretag.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiCacheTest {
    private var now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val a = ApPosition("02:00:00:00:00:01", 48.7001, 21.2501, 20.0)
    private val b = ApPosition("02:00:00:00:00:02", 48.7002, 21.2502, 30.0)
    private val far = ApPosition("02:00:00:00:00:03", 48.7500, 21.2500, 30.0)

    @Test fun ttl() {
        val c = WifiCache({ now })
        c.put(a)
        assertEquals(a, c.get("2:0:0:0:0:1"))
        now += 29 * day
        assertEquals(a, c.get(a.mac))
        now += 2 * day
        assertNull(c.get(a.mac))
        assertEquals(0, c.size)
    }

    @Test fun lruEvictsTheLeastRecentlyUsed() {
        val c = WifiCache({ now }, maxEntries = 2)
        c.put(a); c.put(b)
        c.get(a.mac) // a is now the most recent
        c.put(far)
        assertEquals(a, c.get(a.mac))
        assertNull(c.get(b.mac))
        assertEquals(far, c.get(far.mac))
    }

    @Test fun unknownMacsAreRemembered() {
        val c = WifiCache({ now })
        c.putAll(LookupResult(aps = listOf(a), unknown = listOf("02:00:00:00:00:09")))
        assertTrue(c.isKnownUnknown("02:00:00:00:00:09"))
        assertTrue(c.contains("02:00:00:00:00:09"))
        assertNull(c.get("02:00:00:00:00:09"))
        assertFalse(c.isKnownUnknown(a.mac))
        assertFalse(c.contains("02:00:00:00:00:0a"))
    }

    @Test fun near() {
        val c = WifiCache({ now })
        listOf(a, b, far).forEach { c.put(it) }
        assertEquals(setOf(a.mac, b.mac), c.near(48.7, 21.25, 200.0).map { it.mac }.toSet())
    }

    @Test fun jsonRoundTripDropsExpiredAndSurvivesGarbage() {
        val c = WifiCache({ now })
        c.put(a)
        now += 20 * day
        c.put(b)
        c.putUnknown("02:00:00:00:00:09")
        val s = c.toJson()
        now += 15 * day // a is 35 days old now
        val back = WifiCache.fromJson(s, { now })
        assertNull(back.get(a.mac))
        assertEquals(b, back.get(b.mac))
        assertTrue(back.isKnownUnknown("02:00:00:00:00:09"))
        assertEquals(0, WifiCache.fromJson("{oops", { now }).size)
        assertEquals(0, WifiCache.fromJson(null, { now }).size)
        // LRU order survives: least recently used first
        val d = WifiCache({ now }, maxEntries = 3)
        d.put(a); d.put(b); d.put(far); d.get(a.mac)
        val e = WifiCache.fromJson(d.toJson(), { now }, maxEntries = 3)
        e.put(ApPosition("02:00:00:00:00:04", 48.7, 21.25, 10.0))
        assertNull(e.get(b.mac))
        assertEquals(a, e.get(a.mac))
    }
}

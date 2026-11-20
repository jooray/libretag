package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathLogTest {
    @Test fun blindUntilTheBackfillCameBack() {
        val l = PathLog(blindFrom = 100)
        assertTrue(l.blind)
        l.update(true, 200)
        // a link is up, but the missed reports are not fetched yet
        assertTrue(l.blind)
        l.backfilled(260)
        assertEquals(260L, l.upSince)
        assertFalse(l.blind)
        assertTrue(l.wasBlind(250))
        assertFalse(l.wasBlind(300))
    }

    @Test fun dropAndReturn() {
        val l = PathLog()
        l.update(true, 0); l.backfilled(10)
        l.update(false, 1000)
        assertNull(l.upSince)
        assertEquals(1000L, l.blindFrom)
        // a backfill while no link is up does not count
        l.backfilled(1100)
        assertTrue(l.blind)
        l.update(true, 2000)
        assertTrue(l.blind)
        l.backfilled(2050)
        assertEquals(2050L, l.upSince)
        assertEquals(1000L, l.blindFrom)
        // the same state again changes nothing
        assertFalse(l.update(true, 3000))
    }
}

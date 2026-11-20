package app.libretag.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BeaconDB's 404 ("no position for these networks") is remembered, so it is not asked again. */
class BeaconDbUnknownTest {
    @Test fun notFoundMarksEveryMacUnknownAndTheCacheKeepsIt() {
        val obs = WifiObservation(WifiAp("AA:BB:CC:00:00:01", -60), others = listOf(WifiAp("aa-bb-cc-00-00-02", -70)))
        val r = BeaconDb.notFound(obs.fullMacs)
        assertEquals(listOf("aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02"), r.unknown)
        assertNull(r.direct)
        val cache = WifiCache({ 1_000L })
        assertFalse(WifiAutoLookup.answeredByCache(cache, obs))
        cache.putAll(r, obs.seed!!.mac)
        assertTrue(cache.isKnownUnknown("aa:bb:cc:00:00:01"))
        assertNull(cache.get("aa:bb:cc:00:00:01"))
        // the next automatic lookup of the same seed costs no query
        assertTrue(WifiAutoLookup.answeredByCache(cache, obs))
    }

    @Test fun rateLimitIsSurfaced() {
        assertTrue(WifiHttpException(429).rateLimited)
        assertFalse(WifiHttpException(503).rateLimited)
        // a 429 backs automatic lookups off for at least an hour
        val b = WifiBudget().failed(0L, rateLimited = WifiHttpException(429).rateLimited)
        assertEquals(WifiBudget.Verdict.BACKOFF, b.check("t", WifiBudget.RATE_LIMIT_MIN_MS - 1, lost = true))
    }
}

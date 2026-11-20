package app.libretag.wifi

import app.libretag.data.AppPrefs
import app.libretag.data.Position
import app.libretag.data.Tracker
import app.libretag.data.WifiFixRec
import app.libretag.data.WifiRec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class WifiAutoLookupTest {
    private val now = 1_800_000_000_000L
    private val tag = Tracker(name = "T", keyHex = "")
    private val svc = WifiService.BEACONDB

    private fun report(n: Long = 5, at: Long = now - 60_000L, mode: String = "IDLE", quiet: Boolean = false, reason: String? = null,
                       via: String = "mesh", fix: Boolean = false) =
        Position(fixTime = 0, receivedAt = at, hasFix = fix, lat = 0.0, lon = 0.0, altM = 0, battery = 80, mode = mode, hops = 1,
            snrDb = 0.0, via = via, n = n, quiet = quiet, quietReason = reason, wifi = WifiRec("02:00:00:00:00:01", -60))

    private fun wanted(p: Position, mode: String = AppPrefs.WIFI_AUTO_ALWAYS, t: Tracker = tag, isNew: Boolean = true, service: WifiService = svc) =
        WifiAutoLookup.wanted(mode, service, t, p, isNew, now)

    @Test fun modes() {
        assertFalse(wanted(report(), AppPrefs.WIFI_AUTO_TAP))
        assertTrue(wanted(report(), AppPrefs.WIFI_AUTO_ALWAYS))
        assertFalse(wanted(report(), AppPrefs.WIFI_AUTO_LOST))
        assertTrue(wanted(report(mode = "LOST"), AppPrefs.WIFI_AUTO_LOST))
        assertFalse(wanted(report(), service = WifiService.OFF))
    }

    @Test fun onlyFreshNewReportsWithoutFix() {
        assertFalse(wanted(report(), isNew = false))
        assertFalse(wanted(report(at = now - WifiAutoLookup.FRESH_MS - 1)))
        assertFalse(wanted(report(via = "usb")))
        assertFalse(wanted(report(fix = true)))
        assertFalse(wanted(report().copy(wifi = null)))
        assertFalse(wanted(report(), t = tag.copy(wifiFixes = mapOf(5L to WifiFixRec(0.0, 0.0, 1.0, 2.0, now, "BEACONDB")))))
        assertTrue(wanted(report(via = "nostr")))
    }

    @Test fun quietAtPlaceIsSkipped() {
        assertFalse(wanted(report(quiet = true, reason = "zone")))
        assertTrue(wanted(report(quiet = true, reason = "parked")))
        assertTrue(wanted(report(quiet = true, reason = "owner")))
    }

    @Test fun cacheAnswersKnownAndUnknownSeeds() {
        val c = WifiCache({ now })
        val obs = WifiObservation(WifiAp("02:00:00:00:00:01"))
        assertFalse(WifiAutoLookup.answeredByCache(c, obs))
        c.putUnknown("02:00:00:00:00:01")
        assertTrue(WifiAutoLookup.answeredByCache(c, obs))
    }

    @Test fun directFixIsCachedUnderTheSeed() {
        val c = WifiCache({ now })
        c.putAll(LookupResult(direct = DirectFix(48.70, 21.25, 40.0)), "02:00:00:00:00:01")
        assertEquals(48.70, c.get("02:00:00:00:00:01")!!.lat, 0.0)
    }

    @Test fun perTagGapAndDailyCap() {
        var b = WifiBudget()
        assertEquals(WifiBudget.Verdict.OK, b.check("a", now, false))
        b = b.spent("a", now)
        assertEquals(WifiBudget.Verdict.TAG_GAP, b.check("a", now + 60_000L, false))
        assertEquals(WifiBudget.Verdict.OK, b.check("a", now + 60_000L, lost = true))
        assertEquals(WifiBudget.Verdict.OK, b.check("b", now + 60_000L, false))
        assertEquals(WifiBudget.Verdict.OK, b.check("a", now + WifiBudget.TAG_GAP_MS, false))
        var t = now
        repeat(WifiBudget.DAILY_CAP - 1) { t += 1000; b = b.spent("x$it", t) }
        assertEquals(WifiBudget.Verdict.DAILY_CAP, b.check("new", t + 1000, false))
        assertEquals(WifiBudget.Verdict.OK, b.check("new", now + WifiBudget.WINDOW_MS + 1000 * WifiBudget.DAILY_CAP, false))
    }

    @Test fun backoffDoublesAndEnds() {
        var b = WifiBudget().failed(now)
        assertEquals(WifiBudget.BACKOFF_MIN_MS, b.backoffMs)
        assertEquals(WifiBudget.Verdict.BACKOFF, b.check("a", now + 1000, lost = true))
        b = b.failed(now)
        assertEquals(2 * WifiBudget.BACKOFF_MIN_MS, b.backoffMs)
        repeat(20) { b = b.failed(now) }
        assertEquals(WifiBudget.BACKOFF_MAX_MS, b.backoffMs)
        assertEquals(WifiBudget.RATE_LIMIT_MIN_MS, WifiBudget().failed(now, rateLimited = true).backoffMs)
        b = b.succeeded()
        assertEquals(WifiBudget.Verdict.OK, b.check("a", now + 1000, false))
    }

    @Test fun budgetJsonRoundTrip() {
        val b = WifiBudget().spent("a", now).failed(now)
        assertEquals(b, WifiBudget.fromJson(b.toJson()))
        assertEquals(WifiBudget(), WifiBudget.fromJson("not json"))
        assertEquals(WifiBudget(), WifiBudget.fromJson(null))
    }

    @Test fun httpClientTakesAProxy() {
        assertNull(WifiHttp.client().proxy)
        val p = WifiHttp.client(ProxyConfig.torSocks()).proxy!!
        assertEquals(Proxy.Type.SOCKS, p.type())
        val addr = p.address() as java.net.InetSocketAddress
        assertTrue(addr.isUnresolved)
        assertEquals(9050, addr.port)
    }
}

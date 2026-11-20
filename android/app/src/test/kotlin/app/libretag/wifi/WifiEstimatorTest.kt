package app.libretag.wifi

import app.libretag.locate.Estimate
import app.libretag.locate.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt

class WifiEstimatorTest {
    private val truth = 48.7000 to 21.2500
    private val kx = 111_320.0 * cos(Math.toRadians(truth.first))
    private val ky = 110_540.0

    /** a point [east] / [north] metres from the truth */
    private fun at(east: Double, north: Double) = (truth.first + north / ky) to (truth.second + east / kx)

    private fun ap(i: Int, east: Double, north: Double, acc: Double = 20.0): ApPosition {
        val (lat, lon) = at(east, north)
        return ApPosition("02:00:00:%02x:%02x:%02x".format((i shr 16) and 0xff, (i shr 8) and 0xff, i and 0xff), lat, lon, acc)
    }

    /** RSSI the tracker at the truth would see from [a], no noise */
    private fun rssi(a: ApPosition): Int {
        val d = Geo.distanceM(truth.first, truth.second, a.lat, a.lon).coerceAtLeast(1.0)
        return (WifiEstimator.P0 - 10 * WifiEstimator.N_EXP * log10(d)).roundToInt()
    }

    private fun err(e: WifiEstimate) = Geo.distanceM(e.lat, e.lon, truth.first, truth.second)

    /** hundreds of APs around, as an Apple lookup for the seed returns them */
    private val neighbourhood: List<ApPosition> = Random(7).let { r ->
        (1..300).map { ap(it, r.nextDouble() * 1000 - 500, r.nextDouble() * 1000 - 500) }
    }

    @Test fun nothingInNothingOut() {
        assertNull(WifiEstimator.estimate(emptyList()))
    }

    @Test fun oneApHasAFloor() {
        val a = ap(1, 30.0, 10.0, acc = 5.0)
        val e = WifiEstimator.estimate(listOf(MatchedAp(a, null)))!!
        assertTrue(Geo.distanceM(e.lat, e.lon, a.lat, a.lon) < 10)
        assertTrue("r68 ${e.r68}", e.r68 >= 50)
        assertTrue("r95 ${e.r95}", e.r95 >= 120)
        assertEquals(1, e.apsUsed)
        // a loud single AP is still not better than the floor
        val loud = WifiEstimator.estimate(listOf(MatchedAp(a, -45)))!!
        assertEquals(50.0, loud.r68, 1e-9)
    }

    @Test fun severalApsWithRssi() {
        val aps = listOf(ap(1, 25.0, 0.0), ap(2, -20.0, 15.0), ap(3, 5.0, -35.0), ap(4, -40.0, -30.0))
        val e = WifiEstimator.estimate(aps.map { MatchedAp(it, rssi(it)) })!!
        assertTrue("error ${err(e)}", err(e) < 25)
        assertTrue(err(e) < e.r95)
        assertTrue(e.r68 < 100)
        assertEquals(4, e.apsUsed)
        // without RSSI: the centroid area, wider
        val blind = WifiEstimator.estimate(aps.map { MatchedAp(it, null) })!!
        assertTrue(err(blind) < 40)
        assertTrue(err(blind) < blind.r95)
    }

    @Test fun movedRouterIsIgnored() {
        val good = listOf(ap(1, 20.0, 0.0), ap(2, -15.0, 10.0), ap(3, 0.0, -25.0), ap(4, -30.0, -20.0))
        val moved = ap(9, 900.0, 300.0, acc = 10.0) // moved house, still in the database at the old address
        val e = WifiEstimator.estimate(good.map { MatchedAp(it, rssi(it)) } + MatchedAp(moved, -50))!!
        assertTrue("error ${err(e)}", err(e) < 40)
        // far away (> 1.5 km from the others): dropped before the grid
        val far = ap(10, 5_000.0, 0.0)
        val e2 = WifiEstimator.estimate(good.map { MatchedAp(it, rssi(it)) } + MatchedAp(far, -50))!!
        assertEquals(4, e2.apsUsed)
        assertTrue(err(e2) < 40)
    }

    @Test fun serviceFixCounts() {
        val e = WifiEstimator.estimate(emptyList(), DirectFix(truth.first, truth.second, 60.0))!!
        assertTrue(err(e) < 10)
        assertEquals(60.0, e.r68, 15.0)
        assertEquals(1, e.apsUsed)
    }

    @Test fun seedAndTailsEndToEnd() {
        val seed = ap(999_999, 10.0, 5.0)
        val near = neighbourhood.filter { Geo.distanceM(truth.first, truth.second, it.lat, it.lon) < 100 }.take(5)
        assertTrue(near.size >= 2)
        val obs = WifiObservation(
            seed = WifiAp(seed.mac, rssi(seed)),
            tails = near.map { WifiTail(Mac.tail(it.mac).uppercase(), rssi(it)) } + WifiTail("ee:ee:ee", -80),
        )
        val e = WifiEstimator.fromObservation(obs, LookupResult(aps = neighbourhood + seed))!!
        assertEquals(1 + near.size, e.apsUsed)
        assertEquals(0, e.ambiguousTails)
        assertTrue("error ${err(e)}", err(e) < 30)
    }

    @Test fun tailMatching() {
        val a = ap(0x0a0b01, 0.0, 0.0)
        val twinFar = ApPosition("02:11:11:0a:0b:01", at(400.0, 0.0).first, at(400.0, 0.0).second, 20.0) // same tail, 400 m away
        val b = ap(0x0c0d02, 10.0, 0.0)
        val radio5 = ApPosition("02:22:22:0c:0d:02", at(15.0, 5.0).first, at(15.0, 5.0).second, 20.0) // same tail, same router
        val c = ap(0x0e0f03, 0.0, 30.0)
        val m = TailMatcher.match(
            WifiObservation(
                seed = WifiAp("2:0:0:e:f:3", -60),
                tails = listOf(WifiTail("0a:0b:01", -70), WifiTail("0c0d02", -65), WifiTail("12:34:56"), WifiTail("e:f:3")),
            ),
            listOf(a, twinFar, b, radio5, c),
        )
        assertEquals(listOf("0a:0b:01"), m.ambiguous)
        assertEquals(listOf("12:34:56", "0e:0f:03"), m.unmatched) // the seed is not matched twice
        assertEquals(2, m.matched.size)
        assertEquals(c.mac, m.matched[0].ap.mac)
        assertEquals(-60, m.matched[0].rssiDbm)
        assertEquals(-65, m.matched[1].rssiDbm)
        assertTrue(Geo.distanceM(m.matched[1].ap.lat, m.matched[1].ap.lon, b.lat, b.lon) < 15)

        val e = WifiEstimator.fromObservation(
            WifiObservation(WifiAp(c.mac), listOf(WifiTail("0a:0b:01"))),
            LookupResult(aps = listOf(a, twinFar, c)),
        )!!
        assertEquals(1, e.ambiguousTails)
        assertEquals(1, e.apsUsed)
    }

    private fun repeaters(lat: Double, lon: Double, r68: Double) = Estimate(lat, lon, r68, r68 * 1.6, emptyList(), 100.0, emptyList())

    @Test fun combineWithRepeaters() {
        val wifi = WifiEstimate(truth.first, truth.second, 60.0, 140.0, 3, 0)
        assertEquals(wifi, WifiEstimator.combine(wifi, null))
        // agreeing: repeaters 800 m off with a 1.5 km radius barely move it, and never widen it
        val (rl, ro) = at(800.0, 0.0)
        val c = WifiEstimator.combine(wifi, repeaters(rl, ro, 1_500.0))
        assertFalse(c.conflictWithRepeaters)
        assertTrue(c.r68 <= wifi.r68)
        assertTrue(err(c.copy()) < 5)
        // two similar estimates: the product is narrower than either, in between
        val (bl, bo) = at(40.0, 0.0)
        val p = WifiEstimator.combine(wifi, repeaters(bl, bo, 60.0))
        assertTrue(p.r68 < 60.0)
        assertEquals(20.0, Geo.distanceM(p.lat, p.lon, truth.first, truth.second), 2.0)
        // disagreeing badly: Wi-Fi kept, conflict flagged
        val (fl, fo) = at(8_000.0, 0.0)
        val x = WifiEstimator.combine(wifi, repeaters(fl, fo, 1_000.0))
        assertTrue(x.conflictWithRepeaters)
        assertEquals(wifi.lat, x.lat, 0.0)
        assertEquals(wifi.r68, x.r68, 0.0)
        assertNotNull(x)
    }
}

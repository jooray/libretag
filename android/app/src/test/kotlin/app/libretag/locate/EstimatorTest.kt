package app.libretag.locate

import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatorTest {
    private val repA = Repeater("a5e1000000000a01", "Synthetic-A", 48.6978, 21.2421)
    private val repB = Repeater("b7d2000000000b02", "Synthetic-B", 48.7061, 21.2188)
    private val repC = Repeater("d4a6000000000c03", "Synthetic-C", 48.7182, 21.2556)
    private val truth = 48.700083 to 21.249616

    /** three synthetic repeaters around a synthetic true point */
    @Test fun discoverAnswersMeasured() {
        val e = Estimator.estimate(
            listOf(Anchor(repA, 11.25), Anchor(repB, 6.0), Anchor(repC, -5.75)), null, null,
        )!!
        val err = Geo.distanceM(e.lat, e.lon, truth.first, truth.second)
        assertTrue("error $err m", err < 1_600)
        assertTrue("true point inside 95 % (${e.r95} m)", err < e.r95)
    }

    @Test fun lastFixOnly() {
        // 10 s old: sigma 300 + 20 m/s x 10 s = 500 m, 68 % radius ~1.5 sigma
        val e = Estimator.estimate(emptyList(), truth, 10)!!
        assertTrue(Geo.distanceM(e.lat, e.lon, truth.first, truth.second) < 50)
        assertTrue("r68 ${e.r68}", e.r68 in 600.0..900.0)
        // an hour old: the car may be anywhere around
        assertTrue(Estimator.estimate(emptyList(), truth, 3600)!!.r68 > 5_000)
    }

    @Test fun recentLastFixPullsTheEstimate() {
        val anchors = listOf(Anchor(repA, 11.25), Anchor(repB, 6.0))
        val withFix = Estimator.estimate(anchors, truth, 600)!!
        val without = Estimator.estimate(anchors, null, null)!!
        assertTrue(Geo.distanceM(withFix.lat, withFix.lon, truth.first, truth.second) <
            Geo.distanceM(without.lat, without.lon, truth.first, truth.second))
    }
}

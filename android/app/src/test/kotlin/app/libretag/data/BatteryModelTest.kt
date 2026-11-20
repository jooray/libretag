package app.libretag.data

import app.libretag.data.BatteryEstimate.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt

class BatteryModelTest {
    private val t0 = 1_800_000_000_000L
    private val hour = 3_600_000L

    /** hourly samples; [pct] gives the true level at hour h, [mode] the mode, noise is +-[noise] points */
    private fun series(
        hours: Int,
        noise: Int = 0,
        seed: Long = 1,
        mode: (Int) -> String = { "IDLE" },
        pct: (Int) -> Double,
    ): List<BatterySample> {
        val r = Random(seed)
        return (0..hours).map { h ->
            val n = if (noise > 0) r.nextInt(2 * noise + 1) - noise else 0
            BatterySample(t0 + h * hour, (pct(h).roundToInt() + n).coerceIn(0, 100), mode(h))
        }
    }

    @Test fun nothingBeforeTwoWeeks() {
        val s = series(13 * 24) { 90 - it / 24.0 }
        assertEquals(13.0, BatteryModel.daysOfData(s), 1e-9)
        assertNull(BatteryModel.estimate(s))
        assertEquals(0.0, BatteryModel.daysOfData(emptyList()), 0.0)
        assertNull(BatteryModel.estimate(emptyList()))
    }

    @Test fun steadyDrainWithNoise() {
        // 1 % a day from 75 %, +-2 points of noise, 20 days
        val s = series(20 * 24, noise = 2) { 75 - it / 24.0 }
        val e = BatteryModel.estimate(s)!!
        assertEquals(1.0, e.ratePctPerDay, 0.15)
        val now = s.last().batteryPct
        assertEquals(now / e.ratePctPerDay * 24, e.remainingHours, 1e-6)
        assertTrue(e.remainingHours in 40.0 * 24..70.0 * 24)
        assertEquals(14.0, e.basedOnDays, 0.1)
        assertEquals(Confidence.HIGH, e.confidence)
        assertTrue(!e.capped)
    }

    @Test fun outliersDoNotMoveTheRate() {
        val s = series(20 * 24) { 70 - 0.5 * it / 24.0 }.mapIndexed { i, x ->
            if (i % 37 == 0) x.copy(batteryPct = (x.batteryPct - 15).coerceAtLeast(0)) else x
        }
        assertEquals(0.5, BatteryModel.estimate(s)!!.ratePctPerDay, 0.1)
    }

    @Test fun windowRestartsAfterACharge() {
        // 3 %/day until day 12, charged to 100, then 1 %/day for 6 days
        val s = series(18 * 24) { h -> if (h < 12 * 24) 80 - 3 * h / 24.0 else 100 - (h - 12 * 24) / 24.0 }
        val e = BatteryModel.estimate(s)!!
        assertEquals(1.0, e.ratePctPerDay, 0.2)
        assertEquals(6.0, e.basedOnDays, 0.1)
        assertTrue(e.basedOnDays < 7)
    }

    @Test fun slowChargeOverSeveralReports() {
        // 3 %/day, then a charge seen in four hourly reports (50 -> 62 -> 75 -> 88 -> 100), then 1 %/day
        val c = 12 * 24
        val s = series(19 * 24) { h ->
            when {
                h < c -> 86 - 3 * h / 24.0
                h < c + 4 -> 50 + 12.5 * (h - c + 1)
                else -> 100 - (h - c - 4) / 24.0
            }
        }
        val e = BatteryModel.estimate(s)!!
        assertEquals(1.0, e.ratePctPerDay, 0.2)
        assertTrue(e.basedOnDays < 7)
    }

    @Test fun justChargedFallsBackToThePreviousPace() {
        // 2 %/day for 15 days, charged 12 h ago: too little since then, the old pace is used
        val s = series(15 * 24 + 12) { h -> if (h < 15 * 24) 90 - 2 * h / 24.0 else 100.0 }
        val e = BatteryModel.estimate(s)!!
        assertEquals(2.0, e.ratePctPerDay, 0.2)
        assertEquals(100 / 2.0 * 24, e.remainingHours, 30.0)
        // one level less than the same fit on the current segment
        assertTrue(e.confidence != Confidence.HIGH)
    }

    @Test fun trackAndLostStretchesAreCutOut() {
        // IDLE 1 %/day; a TRACK day (-20) on day 5 and a LOST stretch (-15) on day 10
        fun mode(h: Int) = when (h / 24) { 5 -> "TRACK"; 10 -> "LOST"; else -> "IDLE" }
        fun level(h: Int): Double {
            val d = h / 24.0
            var p = 80 - d
            if (h >= 5 * 24) p -= 20 * minOf(1.0, (h - 5 * 24) / 24.0)
            if (h >= 10 * 24) p -= 15 * minOf(1.0, (h - 10 * 24) / 24.0)
            return p
        }
        val s = series(18 * 24, mode = ::mode, pct = ::level)
        val e = BatteryModel.estimate(s)!!
        assertEquals(1.0, e.ratePctPerDay, 0.15)
        assertTrue("TRACK/LOST time is not IDLE time: ${e.basedOnDays}", e.basedOnDays < 13.5)
    }

    @Test fun flatCurveIsCapped() {
        val s = series(20 * 24, noise = 1) { 95.0 }
        val e = BatteryModel.estimate(s)!!
        assertTrue(e.capped)
        assertEquals(BatteryModel.MAX_REMAINING_HOURS, e.remainingHours, 0.0)
        assertTrue(e.ratePctPerDay >= 0)
    }

    @Test fun onlyTrackingGivesNothing() {
        assertNull(BatteryModel.estimate(series(20 * 24, mode = { "TRACK" }) { 90 - it / 10.0 }))
    }

    @Test fun invalidReadingsAreIgnoredAndOrderDoesNotMatter() {
        val s = series(20 * 24) { 60 - 0.8 * it / 24.0 }
        val messy = (s + BatterySample(t0 + 5 * hour, 255, "IDLE") + BatterySample(t0 + 7 * hour, -1, "IDLE")).shuffled(Random(3))
        assertNotNull(BatteryModel.estimate(messy))
        assertEquals(BatteryModel.estimate(s)!!.ratePctPerDay, BatteryModel.estimate(messy)!!.ratePctPerDay, 1e-9)
    }

    @Test fun highChargeIsLessCertain() {
        val high = BatteryModel.estimate(series(20 * 24) { 100 - 0.4 * it / 24.0 })!!
        val mid = BatteryModel.estimate(series(20 * 24) { 60 - 0.4 * it / 24.0 })!!
        assertTrue(high.confidence < mid.confidence)
    }
}

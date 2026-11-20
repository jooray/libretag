package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterGapsTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L

    private fun pos(n: Long, atMs: Long, quiet: Boolean = false, batt: Int = 80, boot: Boolean = false) = Position(
        fixTime = 0, receivedAt = atMs, hasFix = false, lat = 0.0, lon = 0.0, altM = 0, battery = batt,
        mode = "IDLE", hops = 0, snrDb = 0.0, n = n, quiet = quiet, boot = boot)

    private fun tag(vararg p: Position) = Tracker(name = "T", keyHex = "00", positions = p.toList(), intervalMin = 60)

    @Test fun normalRun() {
        val t = tag(pos(100, now - 3 * h), pos(101, now - 2 * h), pos(102, now - h))
        assertTrue(CounterGaps.gaps(t).all { it.kind == CounterGaps.Kind.NORMAL })
        assertEquals(0.0, CounterGaps.lossRate(t, now), 0.0)
    }

    @Test fun twoLost() {
        val g = CounterGaps.classify(tag(), pos(100, now - 3 * h), pos(103, now))
        assertEquals(CounterGaps.Kind.LOST, g.kind)
        assertEquals(2, g.lost)
    }

    @Test fun epochSkipAfterQuiet() {
        val g = CounterGaps.classify(tag(), pos(100, now - 13 * h, quiet = true), pos(128, now))
        assertEquals(CounterGaps.Kind.EPOCH_SKIP, g.kind)
        assertEquals(0, g.lost)
        // under 12 h it is no epoch skip
        assertEquals(CounterGaps.Kind.LOST, CounterGaps.classify(tag(), pos(126, now - 3 * h, quiet = true), pos(128, now)).kind)
    }

    @Test fun restartOnACheckpoint() {
        // 105 -> 112 within one hour: seven lost reports would take seven hours
        assertEquals(CounterGaps.Kind.RESTART, CounterGaps.classify(tag(), pos(105, now - h), pos(112, now)).kind)
        val t = tag(pos(105, now - h), pos(112, now))
        assertEquals(1, CounterGaps.inferredBoots(t).size)
        assertTrue(CounterGaps.inferredBoots(t)[0].inferred)
    }

    @Test fun restartVersusFifteenLost() {
        // 97 -> 112: in 1 h a restart, in 16 h fifteen lost reports
        assertEquals(CounterGaps.Kind.RESTART, CounterGaps.classify(tag(), pos(97, now - h), pos(112, now)).kind)
        val lost = CounterGaps.classify(tag(), pos(97, now - 16 * h), pos(112, now))
        assertEquals(CounterGaps.Kind.LOST, lost.kind)
        assertEquals(CounterGaps.MAX_LOST_PER_GAP, lost.lost)
    }

    @Test fun bootBitIsARestart() {
        assertEquals(CounterGaps.Kind.RESTART, CounterGaps.classify(tag(), pos(100, now - 3 * h), pos(103, now, boot = true)).kind)
        assertFalse(CounterGaps.inferredBoots(tag(pos(100, now - 3 * h), pos(103, now, boot = true)))[0].inferred)
    }

    @Test fun counterBack() {
        val have = listOf(pos(100, now - 2 * h), pos(101, now - h), pos(102, now))
        // a late copy of the same report is not
        assertFalse(CounterGaps.counterBack(have, pos(100, now - 2 * h)))
        // the same counter with other content is
        assertTrue(CounterGaps.counterBack(have, pos(100, now, batt = 40)))
        // the newest itself and a new counter are not
        assertFalse(CounterGaps.counterBack(have, pos(102, now, batt = 40)))
        assertFalse(CounterGaps.counterBack(have, pos(103, now)))
    }

    @Test fun lossRateOverSevenDays() {
        // 20 reports two hours apart with every other counter lost in the last two days; old gaps do not count
        val ps = mutableListOf(pos(10, now - 9 * 24 * h), pos(20, now - 8 * 24 * h))
        for (i in 0 until 20) ps += pos(100L + 2 * i, now - (40 - 2 * i) * h)
        val t = tag(*ps.toTypedArray())
        assertEquals(19.0 / 39.0, CounterGaps.lossRate(t, now), 1e-9)
        assertEquals(19 to 39, CounterGaps.lossCount(t, now))
    }
}

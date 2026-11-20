package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun pos(agoMin: Long, n: Long = 1, mode: String = "IDLE", batt: Int? = 80, fix: Boolean = true,
                    quiet: Boolean = false, nextInS: Long? = null, lat: Double = 48.70, lon: Double = 21.25) =
        Position(fixTime = 0, receivedAt = now - agoMin * min, hasFix = fix, lat = lat, lon = lon, altM = 0, battery = batt,
            mode = mode, hops = 0, snrDb = 0.0, n = n, quiet = quiet, nextInS = nextInS)

    private fun tag(vararg p: Position, alarm: String? = null, until: Long? = null) =
        Tracker(name = "T", keyHex = "00", positions = p.toList(), alarm = alarm, modeUntil = until, intervalMin = 60)

    @Test fun ok() {
        val s = Status.of(tag(pos(12)), now)
        assertEquals(StatusKind.OK, s.kind)
        assertEquals(now - 12 * min + 60 * min, s.nextAt)
    }

    @Test fun nextInWins() {
        val s = Status.of(tag(pos(5, nextInS = 1800)), now)
        assertEquals(now - 5 * min + 1800_000L, s.nextAt)
    }

    @Test fun silentAfterOneMissedReportPlus30Min() {
        assertEquals(StatusKind.OK, Status.of(tag(pos(85)), now).kind)
        // 0.12: one missed report is tolerated (MissedToleranceTest)
        assertEquals(StatusKind.OK, Status.of(tag(pos(95)), now).kind)
        val s = Status.of(tag(pos(155)), now)
        assertEquals(StatusKind.SILENT, s.kind)
        assertNull(s.nextAt)
    }

    @Test fun quietWaitsForTheHeartbeat() {
        val t = tag(pos(5 * 60, quiet = true)).copy(zones = listOf(Zone("Home", 48.70, 21.25, 150)))
        val s = Status.of(t, now)
        assertEquals(StatusKind.QUIET, s.kind)
        assertEquals("Home", s.quietWhere)
        // 24 h + 30 min later it is silent
        assertEquals(StatusKind.SILENT, Status.of(tag(pos(25 * 60, quiet = true)), now).kind)
    }

    @Test fun priority() {
        // Lost beats an alarm, an alarm beats silence, silence beats low battery
        assertEquals(StatusKind.LOST, Status.of(tag(pos(5, mode = "LOST"), alarm = "guard:1"), now).kind)
        assertEquals(StatusKind.ALARM, Status.of(tag(pos(200), alarm = "guard:1"), now).kind)
        assertEquals(StatusKind.SILENT, Status.of(tag(pos(200, batt = 10)), now).kind)
        assertEquals(StatusKind.LOW_BATTERY, Status.of(tag(pos(5, batt = 18)), now).kind)
        assertEquals(StatusKind.TRACKING, Status.of(tag(pos(1, mode = "TRACK")), now).kind)
        // TRACK that ran out is no longer tracking
        assertEquals(StatusKind.OK, Status.of(tag(pos(1, mode = "TRACK"), until = now - min), now).kind)
        assertEquals(StatusKind.WAITING, Status.of(tag(), now).kind)
    }

    @Test fun sortAlarmsFirstThenByAge() {
        val a = tag(pos(30)).copy(id = "a")
        val b = tag(pos(5)).copy(id = "b")
        val c = tag(pos(40), alarm = "guard:1").copy(id = "c")
        assertEquals(listOf("c", "b", "a"), Status.sort(listOf(a, b, c), now).map { it.id })
    }

    @Test fun marksFillStripesFirst() {
        assertEquals("red" to "stripe", Marks.free(emptyList()))
        assertEquals("blue" to "stripe", Marks.free(listOf("red" to "stripe")))
        val all = Marks.SYMBOLS.flatMap { s -> Marks.COLORS.map { it to s } }
        assertTrue(Marks.free(all.dropLast(1)) == all.last())
    }
}

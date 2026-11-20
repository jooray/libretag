package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class TimelineTest {
    private val z = ZoneOffset.UTC
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).toInstant(z).toEpochMilli()
    private fun pos(ms: Long, n: Long, quiet: Boolean = false) = Position(0, ms, true, 48.70, 21.25, 0, 80, "IDLE", 0, 0.0, n = n, quiet = quiet)

    @Test fun daysNewestFirstWithGapsAndQuiet() {
        val t = Tracker(name = "T", keyHex = "00", intervalMin = 60, zones = listOf(Zone("Home", 48.70, 21.25, 100)), positions = listOf(
            pos(at(15, 20), 1), pos(at(15, 21), 2, quiet = true),
            pos(at(16, 8), 3), pos(at(16, 9), 4), pos(at(16, 15), 5),
        ))
        val days = Timeline.days(t, z, now = at(16, 16))
        assertEquals(2, days.size)
        val today = days[0]
        assertEquals(16, today.date.dayOfMonth)
        // the night after a QUIET report is a quiet stretch, the 6 h hole at noon is a gap
        assertTrue(today.items[0] is Timeline.Quiet)
        assertEquals("Home", (today.items[0] as Timeline.Quiet).where)
        assertTrue(today.items.any { it is Timeline.Gap })
        assertEquals(listOf(3L, 4L, 5L), today.reports.map { it.n })
        assertEquals(listOf(1L, 2L), days[1].reports.map { it.n })
    }

    @Test fun stillQuietNow() {
        val t = Tracker(name = "T", keyHex = "00", positions = listOf(pos(at(16, 8), 1, quiet = true)))
        val d = Timeline.days(t, z, now = at(16, 12))
        val q = d[0].items.last() as Timeline.Quiet
        assertEquals(null, q.to)
    }
}

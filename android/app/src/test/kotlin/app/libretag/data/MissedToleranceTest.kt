package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/design/tag-diagnosis.md 6: one missed report is tolerated; guarded, LOST, TRACK and QUIET get none. */
class MissedToleranceTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun pos(agoMin: Long, n: Long = 1, mode: String = "IDLE", quiet: Boolean = false) = Position(
        fixTime = 0, receivedAt = now - agoMin * min, hasFix = true, lat = 48.70, lon = 21.25, altM = 0, battery = 80,
        mode = mode, hops = 0, snrDb = 0.0, n = n, quiet = quiet)

    private fun tag(vararg p: Position) = Tracker(name = "T", keyHex = "00", positions = p.toList(), intervalMin = 60)

    @Test fun oneMissedIsCalm() {
        // hourly, heard 95 min ago: one report missed, the next one projected an hour later
        val t = tag(pos(95))
        val s = Status.of(t, now)
        assertEquals(StatusKind.OK, s.kind)
        assertEquals(1, s.missed)
        assertEquals(now - 95 * min + 120 * min, s.nextAt)
        assertEquals(StatusLines.Plate.MISSED, StatusLines.plate(t, s, now))
        assertEquals(now - 95 * min + 150 * min, Status.silentAt(t, now))
        // 2 h 35 min: the second one is missed too
        assertEquals(StatusKind.SILENT, Status.of(tag(pos(155)), now).kind)
    }

    @Test fun guardedLostTrackAndQuietGetNone() {
        val guarded = tag(pos(95)).copy(guardLat = 48.70, guardLon = 21.25)
        assertEquals(StatusKind.SILENT, Status.of(guarded, now).kind)
        assertEquals(0, Status.allowedMissed(tag(pos(45, mode = "LOST")), now))
        assertEquals(StatusKind.LOST, Status.of(tag(pos(45, mode = "LOST")), now).kind)
        assertEquals(0, Status.allowedMissed(tag(pos(45, mode = "TRACK")), now))
        assertEquals(StatusKind.SILENT, Status.of(tag(pos(45, mode = "TRACK")), now).kind)
        assertEquals(0, Status.allowedMissed(tag(pos(25 * 60, quiet = true)), now))
        assertEquals(StatusKind.SILENT, Status.of(tag(pos(25 * 60, quiet = true)), now).kind)
    }

    @Test fun lossyTagGetsTwo() {
        // every other report lost over the last day
        val ps = (0 until 12).map { pos((40 - 2 * it) * 60L, n = 100L + 2 * it) }
        val t = tag(*ps.toTypedArray())
        assertEquals(2, Status.allowedMissed(t, now))
    }
}

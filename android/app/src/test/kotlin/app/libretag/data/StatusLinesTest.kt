package app.libretag.data

import app.libretag.data.StatusLines.NextKind
import app.libretag.data.StatusLines.Plate
import app.libretag.data.StatusLines.ReachKind
import app.libretag.data.StatusLines.SubKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status vocabulary (ui-review §3): each block one kind of time, a single next report, a quiet
 * tag's sign of life is a deadline, listen times only in the command line, no heard time on the
 * plate's second line, low battery never hides the state on the detail.
 */
class StatusLinesTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val home = Zone("Home", 48.70, 21.25, 150)

    private fun pos(agoMin: Long, n: Long = 1, mode: String = "IDLE", batt: Int? = 80, fix: Boolean = true,
                    quiet: Boolean = false, reason: String? = null, checks: Boolean = false, nextInS: Long? = null,
                    approxUntil: Long? = null) =
        Position(fixTime = 0, receivedAt = now - agoMin * min, hasFix = fix, lat = 48.70, lon = 21.25, altM = 0, battery = batt,
            mode = mode, hops = 0, snrDb = 0.0, n = n, quiet = quiet, quietReason = reason, sync = checks, nextInS = nextInS,
            approxUntil = approxUntil)

    private fun tag(vararg p: Position, type: String = "thing") =
        Tracker(name = "Car", keyHex = "00", type = type, positions = p.toList(), zones = listOf(home), intervalMin = 60, createdAt = now - 600 * min)

    private fun plan(p: Position) = CheckPlan(p.n, p.receivedAt, 60, listOf(3500L, 7300L, 10700L))

    private fun lines(t: Tracker, lowBattery: Boolean = false) = Status.of(t, now, lowBattery).let { it to StatusLines.of(t, it, now) }

    /** Every state the app can show, for the rules that hold for all of them. */
    private fun all(): List<Tracker> {
        val q = pos(70, 12, fix = true, quiet = true, reason = "zone", checks = true, nextInS = 24 * 3600L)
        val o = pos(70, 13, fix = false, quiet = true, reason = "owner", checks = true, nextInS = 8 * 3600L)
        val k = pos(70, 14, quiet = true, reason = "parked", checks = false, nextInS = 6 * 3600L)
        val ev = pos(10, 20, checks = true)
        return listOf(
            tag(),
            tag(pos(12)),
            tag(pos(70)),
            tag(pos(12)).copy(leftPlace = "Home", leftAt = now - 12 * min),
            tag(q).copy(anchor = AnchorState(a = plan(q))),
            tag(o).copy(anchor = AnchorState(a = plan(o))),
            tag(k),
            tag(k, type = "vehicle"),
            tag(pos(1, mode = "TRACK")).copy(modeUntil = now + 59 * min),
            tag(pos(1, mode = "LOST")).copy(modeUntil = now + 23 * 60 * min),
            tag(pos(5)).copy(guardLat = 48.0, guardLon = 21.0, guardAlerted = true, alarm = Places.guardAlarm(now - 5 * min)),
            tag(pos(200)),
            tag(pos(5, batt = 12)),
            tag(pos(30, approxUntil = now - 20 * min)),
            tag(ev).copy(reportEvery = 3, intervalMin = 20, anchor = AnchorState(a = CheckPlan(ev.n, ev.receivedAt, 20, listOf(1200L, 2400L)))),
        )
    }

    @Test fun platesForEveryState() {
        val plates = all().map { lines(it).second.plate }
        assertEquals(listOf(Plate.WAITING, Plate.HEARD, Plate.HEARD_LATE, Plate.LEFT, Plate.QUIET_PLACE, Plate.QUIET_WITH_YOU,
            Plate.QUIET_STILL, Plate.QUIET_PARKED, Plate.TRACKING, Plate.LOST, Plate.GUARD_ALARM, Plate.NOT_HEARD,
            // the detail: low battery keeps the state underneath
            Plate.HEARD, Plate.HEARD, Plate.HEARD), plates)
        // the list keeps the battery sentence
        assertEquals(Plate.LOW_BATTERY, lines(all()[12], lowBattery = true).second.plate)
    }

    @Test fun onlyTrackingHasAFutureTimeOnThePlateAndOnlyLostOnLineTwo() {
        for (t in all()) {
            val (_, l) = lines(t)
            if (l.plate != Plate.TRACKING) assertNull("${l.plate}", l.plateAhead)
            if (l.plate != Plate.LOST) assertNull("${l.plate}", l.sub.ahead)
        }
        assertEquals(now + 59 * min, lines(all()[8]).second.plateAhead)
        assertEquals(now + 23 * 60 * min, lines(all()[9]).second.sub.ahead)
    }

    @Test fun theHeardTimeIsNeverOnLineTwo() {
        for (t in all()) {
            val (s, l) = lines(t)
            when (l.plate) {
                Plate.HEARD, Plate.HEARD_LATE, Plate.LEFT, Plate.TRACKING -> assertEquals("${l.plate}", SubKind.NONE, l.sub.kind)
                else -> {}
            }
            // "since" is when the quiet stretch began, a past moment
            l.sub.past?.let { assertTrue("${l.plate}", it <= now) }
            if (l.sub.kind == SubKind.GUARD_LEFT) assertEquals(now - 5 * min, l.sub.past)
            if (l.sub.kind == SubKind.SINCE) assertTrue(s.heardAt != null)
        }
    }

    @Test fun oneNextReportAndNoListenTimeInIt() {
        for (t in all()) {
            val (s, l) = lines(t)
            // a listen time never shows as the next report
            if (s.nextCheckAt != null && s.nextCheckAt != s.nextAt) assertNotEquals("${l.plate}", s.nextCheckAt, l.next.ahead?.at)
            // the next report's time is the report time, when there is one
            l.next.ahead?.let { assertEquals("${l.plate}", s.nextAt, it.at) }
        }
    }

    @Test fun quietSignOfLifeIsADeadlineWithTheListenIntervalAsADuration() {
        for (i in listOf(4, 5, 6, 7)) {
            val (s, l) = lines(all()[i])
            assertTrue(l.next.ahead!!.deadline)
            assertEquals(s.checkEveryS, l.next.withinS)
        }
        assertEquals(NextKind.WHEN_LEAVES, lines(all()[4]).second.next.kind)
        assertEquals("Home", lines(all()[4]).second.place)
        assertEquals(3600, lines(all()[4]).second.next.withinS)
        assertEquals(NextKind.WHEN_PART, lines(all()[5]).second.next.kind)
        assertEquals(NextKind.WHEN_MOVES, lines(all()[6]).second.next.kind)
        // no listening between reports: no bracket
        assertNull(lines(all()[6]).second.next.withinS)
    }

    @Test fun estimatesAndDeadlinesAreNeverMixed() {
        for (t in all()) {
            val (_, l) = lines(t)
            val a = l.next.ahead ?: continue
            when (l.next.kind) {
                NextKind.REPORT, NextKind.DUE -> assertFalse("${l.plate}", a.deadline)
                else -> assertTrue("${l.plate}", a.deadline)
            }
        }
    }

    @Test fun approximateReportHasDeadlinesOnly() {
        val (s, l) = lines(all()[13])
        assertTrue(s.approx)
        assertEquals(NextKind.LATEST, l.next.kind)
        assertTrue(l.next.ahead!!.deadline)
        assertNull(l.next.withinS)
        assertEquals(ReachKind.WITH_REPORT, l.reach.kind)
        assertTrue(l.reach.ahead!!.deadline)
    }

    @Test fun theCommandLineHasTheOneTimeACommandGetsThrough() {
        for (t in all()) {
            val (s, l) = lines(t)
            when (s.kind) {
                StatusKind.WAITING -> assertEquals(ReachKind.FIRST, l.reach.kind)
                StatusKind.SILENT -> assertEquals(ReachKind.WHEN_HEARD, l.reach.kind)
                else -> assertEquals("${l.plate}", s.commandAt, l.reach.ahead?.at)
            }
        }
        // quiet and listening: at the next listen, an estimate
        val (q, lq) = lines(all()[4])
        assertEquals(ReachKind.AT, lq.reach.kind)
        assertEquals(q.nextCheckAt, lq.reach.ahead!!.at)
        assertFalse(lq.reach.ahead!!.deadline)
        // quiet, not listening: with the next report, at the latest
        val (k, lk) = lines(all()[6])
        assertEquals(ReachKind.WITH_REPORT, lk.reach.kind)
        assertEquals(k.nextAt, lk.reach.ahead!!.at)
        // reporting every 3rd wake-up: the command line says the listen time, the When row the report
        val (e, le) = lines(all()[14])
        assertEquals(e.nextCheckAt, le.reach.ahead!!.at)
        assertEquals(e.nextAt, le.next.ahead!!.at)
        assertNotEquals(le.reach.ahead!!.at, le.next.ahead!!.at)
    }

    @Test fun lateReportIsDueNotNext() {
        val (s, l) = lines(all()[2])
        assertEquals(NextKind.DUE, l.next.kind)
        assertTrue(s.nextAt!! < now)
    }

    @Test fun leftAPlaceIsNotAnAlarm() {
        val (s, l) = lines(all()[3])
        assertEquals(StatusKind.LEFT, s.kind)
        assertEquals(TagStatus.Level.NONE, s.level)
        assertEquals("Home", l.place)
        assertEquals(NextKind.REPORT, l.next.kind)
    }

    @Test fun quietSinceIsTheStartOfTheStretch() {
        val a = pos(300, 10, quiet = true, reason = "zone")
        val b = pos(200, 11, quiet = true, reason = "zone")
        val c = pos(100, 12, quiet = true, reason = "zone")
        assertEquals(a.receivedAt, Status.quietSince(tag(pos(400, 9), a, b, c)))
        // another reason ends the stretch
        assertEquals(b.receivedAt, Status.quietSince(tag(pos(300, 10, quiet = true, reason = "owner"), b, c)))
        assertNull(Status.quietSince(tag(pos(5))))
    }

    @Test fun waitingSaysWhenItWasPairedAndTheInterval() {
        val (_, l) = lines(all()[0])
        assertEquals(SubKind.PAIRED, l.sub.kind)
        assertEquals(now - 600 * min, l.sub.past)
        assertEquals(NextKind.FIRST_WITHIN, l.next.kind)
        assertEquals(3600, l.next.intervalS)
    }
}

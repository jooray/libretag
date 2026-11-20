package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two times for a quiet tag (0.7): next report (heartbeat) and next check. */
class Checks07StatusTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun quiet(agoMin: Long, n: Long, checks: Boolean, reason: String = "owner") = Position(
        fixTime = 0, receivedAt = now - agoMin * min, hasFix = false, lat = 0.0, lon = 0.0, altM = 0, battery = 80,
        mode = "IDLE", hops = 0, snrDb = 0.0, n = n, quiet = true, quietReason = reason, nextInS = 8 * 3600L, checks = checks)

    private fun plan(p: Position) = CheckPlan(p.n, p.receivedAt, 60, listOf(3500L, 7300L, 10700L))

    @Test fun quietWithChecksHasBothTimes() {
        val p = quiet(70, 12, true)
        val t = Tracker(name = "Car", keyHex = "00", positions = listOf(p), checkPlan = plan(p))
        val s = Status.of(t, now)
        assertEquals(StatusKind.QUIET, s.kind)
        assertEquals("owner", s.quietWhere)
        assertEquals(p.receivedAt + 8 * 3600_000L, s.nextAt)
        // the first check (58 min) has passed, the second is at 2 h 1 min 40 s
        assertEquals(p.receivedAt + 7300_000L, s.nextCheckAt)
        assertEquals(3600, s.checkEveryS)
        assertEquals(s.nextCheckAt, s.commandAt)
    }

    @Test fun firmware06HasOnlyTheReport() {
        val p = quiet(70, 12, false)
        val s = Status.of(Tracker(name = "Car", keyHex = "00", positions = listOf(p), checkPlan = plan(p)), now)
        assertNull(s.nextCheckAt)
        assertEquals(s.nextAt, s.commandAt)
    }

    @Test fun planOfAnOlderReportIsIgnored() {
        val p = quiet(70, 12, true)
        val s = Status.of(Tracker(name = "Car", keyHex = "00", positions = listOf(p), checkPlan = plan(p).copy(n = 11)), now)
        assertNull(s.nextCheckAt)
    }

    @Test fun sharedTagDoesNotKnowTheInterval() {
        val p = quiet(70, 12, true)
        val s = Status.of(Tracker(name = "Car", keyHex = "", sharedBy = "x", positions = listOf(p), checkPlan = plan(p)), now)
        assertNull(s.nextCheckAt)
    }

    @Test fun afterTheLastCheckOnlyTheHeartbeat() {
        val p = quiet(200, 12, true)
        val s = Status.of(Tracker(name = "Car", keyHex = "00", positions = listOf(p), checkPlan = plan(p)), now)
        assertNull(s.nextCheckAt)
        assertEquals(p.receivedAt + 8 * 3600_000L, s.nextAt)
    }

    @Test fun parkedIsQuiet() {
        val p = quiet(10, 3, true, "parked")
        assertEquals("parked", Status.of(Tracker(name = "Car", keyHex = "00", positions = listOf(p)), now).quietWhere)
    }

    @Test fun reportEveryThreeShowsTheNextCheckBetweenReports() {
        // 0.7.3: wake every hour, report every 3 h; a normal IDLE report with CHECKS
        val p = quiet(70, 12, true).copy(quiet = false, quietReason = null, nextInS = 10_800L)
        val t = Tracker(name = "Car", keyHex = "00", positions = listOf(p), intervalMin = 60, reportEvery = 3,
            checkPlan = CheckPlan(12, p.receivedAt, 60, listOf(3500L, 7300L)))
        assertEquals(180, t.reportPeriodMin)
        val s = Status.of(t, now)
        assertEquals(StatusKind.OK, s.kind)
        assertEquals(p.receivedAt + 10_800_000L, s.nextAt)
        assertEquals(p.receivedAt + 7300_000L, s.nextCheckAt)
        assertEquals(3600, s.checkEveryS)
        // reporting at every wake-up: no checks in between
        assertNull(Status.of(t.copy(reportEvery = 1), now).nextCheckAt)
        assertEquals(180 * 60, Status.intervalS(t, null, now))
    }

    @Test fun splitForPeriodListensHourly() {
        assertEquals(60 to 3, Status.splitForPeriod(180, 180, 1, true))   // was "with each report": hourly
        assertEquals(60 to 6, Status.splitForPeriod(360, 60, 3, true))
        assertEquals(30 to 12, Status.splitForPeriod(360, 30, 6, true))   // a finer choice that fits stays
        assertEquals(60 to 2, Status.splitForPeriod(120, 45, 4, true))    // does not fit: hourly
        assertEquals(60 to 1, Status.splitForPeriod(60, 60, 1, true))
        assertEquals(15 to 2, Status.splitForPeriod(30, 15, 4, true))
        assertEquals(180 to 1, Status.splitForPeriod(180, 60, 3, false))  // old firmware: no split
    }

    @Test fun wakeSplit() {
        assertEquals(60 to 3, Status.wakeSplit(180, 60))
        assertEquals(15 to 4, Status.wakeSplit(60, 15))
        assertEquals(60 to 1, Status.wakeSplit(60, 60))
        assertEquals(30 to 1, Status.wakeSplit(30, 60))     // checking less often than reporting: no
        assertEquals(120 to 1, Status.wakeSplit(120, 45))   // must divide the report period
        assertEquals(15 to 24, Status.wakeSplit(360, 15))
    }

    @Test fun checkPlanNext() {
        val c = CheckPlan(1, 1000, 60, listOf(10, 20))
        assertEquals(1 to 11_000L, c.next(1000))
        assertEquals(2 to 21_000L, c.next(11_000))
        assertNull(c.next(21_000))
        assertEquals(21_000L, c.at(2))
    }

    @Test fun usePreset() {
        val dev = KnownDevice("AA:BB:CC:DD:EE:FF", "Pixel 9")
        val zone = Zone("Home", 48.70, 21.25, 150)
        val thing = Tracker(name = "Car", keyHex = "00", type = "vehicle", zones = listOf(zone), knownDevices = listOf(dev))
        val child = thing.copy(type = "person")
        assertEquals(Tracker.USE_THINGS, thing.useKind)
        assertEquals(Tracker.USE_PEOPLE, child.useKind)
        assertEquals(3, thing.stillAfterEff)
        assertEquals(0, child.stillAfterEff)
    }

    @Test fun trackDefaultsToFiveMinutes() {
        assertEquals(300, Status.TRACK_INTERVAL_S)
        assertEquals(listOf(120, 300, 600, 900, 1800), Status.TRACK_CHOICES_S)
        assertEquals(listOf(15, 30, 60, 120, 180, 360), Status.IDLE_CHOICES_MIN)
    }
}

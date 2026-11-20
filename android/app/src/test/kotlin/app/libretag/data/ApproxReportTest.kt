package app.libretag.data

import app.libretag.nostr.NostrEvent
import app.libretag.nostr.ReportSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.8.1/0.8.2: reports a bridge drained late (["approx", …]) never anchor the check schedule. */
class ApproxReportTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun quiet(n: Long, atMs: Long, approxUntil: Long? = null, via: String = "nostr") = Position(
        fixTime = 0, receivedAt = atMs, hasFix = true, lat = 48.70, lon = 21.25, altM = 0, battery = 77,
        mode = "IDLE", hops = null, snrDb = 0.0, via = via, n = n, quiet = true, quietReason = "owner",
        nextInS = 8 * 3600L, checks = true, approxUntil = approxUntil)

    private fun plan(p: Position) = CheckPlan(p.n, p.receivedAt, 60, listOf(3500L, 7300L, 10700L))

    private fun ev(tags: List<List<String>>, createdAt: Long = 1_800_000_000L) =
        NostrEvent("00".repeat(32), createdAt, 4243, tags, "")

    @Test fun approxTagIsParsed() {
        // 0.8.2: created_at is the latest possible time, the approx value the earliest
        // (the same cases as report_time in host/tests/vectors/lt2.json)
        val ca = 1_800_000_000L
        val cases = listOf(
            listOf<List<String>>() to (ca to null),
            listOf(listOf("approx", "${ca - 3600}")) to (ca - 3600 to ca),
            listOf(listOf("approx", "$ca")) to (ca to ca),
            listOf(listOf("approx", "${ca + 600}")) to (ca to ca + 600),  // bridge 0.3.0 order
            listOf(listOf("approx", "x")) to (ca to ca),                  // malformed: still approximate
            listOf(listOf("approx", "-5")) to (ca to ca),
            listOf(listOf("approx")) to (ca to ca),
            listOf(listOf("y", "00".repeat(16)), listOf("expiration", "${ca + 14 * 86400}"), listOf("approx", "${ca - 60}")) to (ca - 60 to ca),
        )
        for ((tags, want) in cases) {
            val (from, until) = want
            assertEquals(tags.toString(), from * 1000 to until?.let { it * 1000 }, ReportSync.reportTimeMs(ev(tags, ca)))
        }
    }

    @Test fun approxReportIsShownButHasNoChecks() {
        val p = quiet(12, now - 300 * min, approxUntil = now - 60 * min)
        // even a plan stored for this n (it never should be) is not used
        val t = Tracker(name = "Car", keyHex = "00", lastN = 12, positions = listOf(p), checkPlan = plan(p))
        assertNull(Status.checkPlan(t))
        val s = Status.of(t, now)
        assertEquals(StatusKind.QUIET, s.kind)
        assertTrue(s.approx)
        assertEquals(77, s.battery)
        assertEquals(p.receivedAt, s.heardAt)
        assertNull(s.nextCheckAt)
        // "next report by": from the latest time it can have been heard
        assertEquals(p.approxUntil!! + 8 * 3600_000L, s.nextAt)
        assertEquals(s.nextAt, s.commandAt)
    }

    @Test fun approxNormalReportNextIsFromDrainTime() {
        val p = quiet(12, now - 200 * min, approxUntil = now - 30 * min).copy(quiet = false, nextInS = null, checks = false)
        val t = Tracker(name = "Car", keyHex = "00", lastN = 12, positions = listOf(p), intervalMin = 60)
        val s = Status.of(t, now)
        assertEquals(StatusKind.OK, s.kind) // not silent: the drain time bounds it
        assertEquals(now + 30 * min, s.nextAt)
    }

    @Test fun exactCopyWins() {
        val a = quiet(12, now - 300 * min, approxUntil = now - 60 * min)
        val exact = quiet(12, now - 100 * min, via = "mesh")
        val t = Tracker(name = "Car", keyHex = "00", lastN = 12, positions = listOf(a))
        assertTrue(ReportMerge.upgrades(t, exact))
        val merged = ReportMerge.merge(t.positions, exact, 100)
        assertEquals(listOf(exact), merged)
        val t2 = t.copy(positions = merged, checkPlan = plan(exact))
        assertEquals(plan(exact), Status.checkPlan(t2))
        assertEquals(exact.receivedAt + 7300_000L, Status.of(t2, now).nextCheckAt)
        // a later approximate copy does not replace the exact one
        val late = quiet(12, now - 400 * min, approxUntil = now - 5 * min)
        assertTrue(ReportMerge.redundant(merged, late))
        assertSame(merged, ReportMerge.merge(merged, late, 100))
        assertFalse(ReportMerge.upgrades(t2, exact))
    }

    @Test fun olderReportIsNoUpgrade() {
        val a = quiet(11, now - 300 * min, approxUntil = now - 60 * min)
        val b = quiet(12, now - 50 * min, via = "mesh")
        val t = Tracker(name = "Car", keyHex = "00", lastN = 12, positions = listOf(a, b))
        assertFalse(ReportMerge.upgrades(t, quiet(11, now - 280 * min)))
    }

    @Test fun twoApproxCopiesNarrowTheRange() {
        val a = quiet(12, now - 300 * min, approxUntil = now - 60 * min)
        val b = quiet(12, now - 200 * min, approxUntil = now - 10 * min)
        val m = ReportMerge.merge(listOf(a), b, 100).single()
        assertEquals(now - 200 * min, m.receivedAt)
        assertEquals(now - 60 * min, m.approxUntil)
    }
}

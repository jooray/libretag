package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10 % warning: once per crossing, from a report or the tag's own DM, deduplicated. */
class CritBatteryTest {
    private val t0 = Tracker(name = "Keys", keyHex = "", createdAt = 1_000L)

    @Test fun parsesTheFirmwareText() {
        assertEquals(9, CritBattery.parseDm("batt 9%"))
        assertEquals(10, CritBattery.parseDm("batt 10 %"))
        assertNull(CritBattery.parseDm("LibreTag Keys: battery 20 %, please charge me."))
        assertNull(CritBattery.parseDm("batt 4.22V"))
    }

    @Test fun reportThenDmNotifiesOnce() {
        val a = CritBattery.onReport(t0, 9, isNew = true, atMs = 10_000)
        assertTrue(a.notify)
        val b = CritBattery.onDm(a.tracker, 9, 11_000)
        assertFalse(b.notify)
        assertFalse(CritBattery.onReport(b.tracker, 8, true, 12_000).notify)
    }

    @Test fun dmThenReportNotifiesOnce() {
        val a = CritBattery.onDm(t0, 9, 10_000)
        assertTrue(a.notify)
        assertFalse(CritBattery.onReport(a.tracker, 7, true, 11_000).notify)
    }

    @Test fun rechargeStartsANewCrossing() {
        val low = CritBattery.onReport(t0, 9, true, 10_000).tracker
        val charged = CritBattery.onReport(low, 95, true, 20_000).tracker
        assertFalse(charged.critBattNotified)
        // the old DM (relays keep it 14 days) arrives again: it belongs to the past crossing
        assertFalse(CritBattery.onDm(charged, 9, 9_000).notify)
        assertTrue(CritBattery.onDm(charged, 9, 30_000).notify)
    }

    @Test fun hysteresisBetween10And20() {
        val low = CritBattery.onReport(t0, 10, true, 10_000).tracker
        val mid = CritBattery.onReport(low, 15, true, 11_000)
        assertTrue(mid.tracker.critBattNotified)
        assertFalse(CritBattery.onReport(mid.tracker, 9, true, 12_000).notify)
    }

    @Test fun oldReportsAndUnknownBatteryDoNothing() {
        assertFalse(CritBattery.onReport(t0, 5, isNew = false, atMs = 10_000).notify)
        assertFalse(CritBattery.onReport(t0, null, isNew = true, atMs = 10_000).notify)
        assertFalse(CritBattery.onDm(t0, 9, 500).notify) // older than the tag in this app
    }
}

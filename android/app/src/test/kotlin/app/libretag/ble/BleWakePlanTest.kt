package app.libretag.ble

import app.libretag.ble.BleWakePlan.Step
import app.libretag.data.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleWakePlanTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val h = 60 * min

    @Test fun waitsTwoHoursOrTwoReportPeriods() {
        assertEquals(2 * h, BleWakePlan.waitMs(Tracker(name = "A", keyHex = "00", intervalMin = 15)))
        assertEquals(6 * h, BleWakePlan.waitMs(Tracker(name = "A", keyHex = "00", intervalMin = 180)))
        // a report every 4 wake-ups of 60 min: 4 h apart
        assertEquals(8 * h, BleWakePlan.waitMs(Tracker(name = "A", keyHex = "00", intervalMin = 60, reportEvery = 4)))
        val r = BleWakePlan.request(Tracker(name = "A", keyHex = "00", intervalMin = 30), now)
        assertEquals(now + 2 * h, r.untilMs)
        assertTrue(r.waiting)
    }

    @Test fun steps() {
        val r = WakeRequest("t", now, now + 2 * h)
        assertEquals(Step.KEEP, BleWakePlan.step(r, null, now + min))
        // a console window from before the request is not the answer
        assertEquals(Step.KEEP, BleWakePlan.step(r, now - min, now + min))
        assertEquals(Step.ANSWERED, BleWakePlan.step(r, now + 30 * min, now + 30 * min))
        assertEquals(Step.TIMED_OUT, BleWakePlan.step(r, null, now + 2 * h))
        val a = r.copy(answeredAt = now + 30 * min)
        assertFalse(a.waiting)
        assertEquals(Step.KEEP, BleWakePlan.step(a, now + 30 * min, now + 32 * min))
        assertEquals(Step.DONE, BleWakePlan.step(a, now + 30 * min, now + 34 * min))
    }

    @Test fun nextWake() {
        assertEquals(now + 5 * min, BleWakePlan.nextWakeAt(now, now - min, null, now + 5 * min, now + 9 * min))
        assertNull(BleWakePlan.nextWakeAt(now, now - min, null))
    }

    @Test fun askedWindowIsNoRestart() {
        val t = Tracker(name = "Car", keyHex = "00")
        assertTrue(BleBoots.onAdvert(t, "console", "aabbccddeeff", -60, now).bleBoots.isNotEmpty())
        assertTrue(BleBoots.onAdvert(t, "console", "aabbccddeeff", -60, now, asked = true).bleBoots.isEmpty())
    }
}

package app.libretag.data

import app.libretag.CommandKind
import app.libretag.proto.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPlanTest {
    @Test fun reportsAndTheDailyLimit() {
        assertEquals(12, TrackPlan.reports(300, 60))
        // the firmware raises 60 s to 120 s
        assertEquals(30, TrackPlan.reports(60, 60))
        assertFalse(TrackPlan.hitsCap(300, 720)) // 144 in 12 h
        assertTrue(TrackPlan.hitsCap(300, 1440)) // 288 in 24 h
        assertTrue(TrackPlan.hitsCap(120, 480)) // 240 in 8 h
        assertEquals(800, TrackPlan.capAfterMin(300, 1440))
        assertNull(TrackPlan.capAfterMin(1800, 1440))
    }

    @Test fun theCommandCarriesTheDuration() {
        val c = CommandKind.TRACK.build(3, 600, durationMin = 240)
        assertEquals(600, Command.modeIntervalS(c))
        assertEquals(240, Command.modeDurationMin(c))
        // one tap: 1 h every 5 min
        val d = CommandKind.TRACK.build(3)
        assertEquals(Status.TRACK_INTERVAL_S, Command.modeIntervalS(d))
        assertEquals(CommandKind.TRACK_MIN, Command.modeDurationMin(d))
    }
}

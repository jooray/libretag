package app.libretag.data

import app.libretag.data.SilentNotify.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentNotifyTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L
    private val silent = TagStatus(StatusKind.SILENT, now - 3 * h, null)
    private fun d(c: Diagnosis.Cause) = Diagnosis.Result(c, Diagnosis.Confidence.HIGH)

    @Test fun decisions() {
        assertEquals(Out.POST, SilentNotify.decide(false, true, silent, null))
        assertEquals(Out.POST, SilentNotify.decide(false, true, silent, d(Diagnosis.Cause.UNKNOWN)))
        assertEquals(Out.NONE, SilentNotify.decide(true, true, silent, d(Diagnosis.Cause.UNKNOWN)))
        assertEquals(Out.NONE, SilentNotify.decide(false, false, silent, null))
        // the phone was blind: held, posted later
        assertEquals(Out.HOLD, SilentNotify.decide(false, true, silent, d(Diagnosis.Cause.PHONE_OFFLINE)))
        assertEquals(Out.HOLD, SilentNotify.decide(false, true, silent, d(Diagnosis.Cause.JUST_BACK)))
        // evidence arrived after a post: taken back
        assertEquals(Out.CANCEL, SilentNotify.decide(true, true, silent, d(Diagnosis.Cause.WITH_YOU)))
        assertEquals(Out.CANCEL, SilentNotify.decide(true, true, silent, d(Diagnosis.Cause.BOOTLOADER)))
        assertEquals(Out.NONE, SilentNotify.decide(false, true, silent, d(Diagnosis.Cause.BOOTLOADER)))
        // not silent: nothing
        assertEquals(Out.NONE, SilentNotify.decide(false, true, silent.copy(kind = StatusKind.OK), null))
    }

    @Test fun restartOncePerEpisode() {
        val t = Tracker(name = "Car", keyHex = "00", bleBoots = listOf(now - 40 * 60_000L, now - 5 * 60_000L))
        val start = SilentNotify.restartNotice(t, now)!!
        assertNull(SilentNotify.restartNotice(t.copy(restartNotified = start), now))
        // 6 h later a new episode
        val later = now + 7 * h
        val t2 = t.copy(restartNotified = start, bleBoots = listOf(later - 30 * 60_000L, later - 60_000L))
        assertTrue(SilentNotify.restartNotice(t2, later) != null)
    }

    @Test fun updateNoticeAfter30Min() {
        val t = Tracker(name = "Car", keyHex = "00", dfuStartedAt = now - 40 * 60_000L)
        val d = d(Diagnosis.Cause.UPDATE_UNFINISHED)
        assertTrue(SilentNotify.updateNotice(t, d, now))
        assertFalse(SilentNotify.updateNotice(t.copy(dfuNotified = true), d, now))
        assertFalse(SilentNotify.updateNotice(t.copy(dfuStartedAt = now - 10 * 60_000L), d, now))
    }

    @Test fun blindNoticeOnlyWithAGuardedTag() {
        val guarded = Tracker(name = "Car", keyHex = "00", guardLat = 48.70, guardLon = 21.25)
        val c = DiagContext(pathUpSince = null, blindFrom = now - 3 * h)
        assertTrue(SilentNotify.blindNotice(listOf(guarded), c, now, 0))
        assertFalse(SilentNotify.blindNotice(listOf(guarded), c, now, now - 3 * h))
        assertFalse(SilentNotify.blindNotice(listOf(guarded.copy(guardLat = null)), c, now, 0))
        assertFalse(SilentNotify.blindNotice(listOf(guarded), c.copy(blindFrom = now - h), now, 0))
    }
}

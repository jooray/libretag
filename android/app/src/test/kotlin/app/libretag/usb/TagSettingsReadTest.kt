package app.libretag.usb

import app.libretag.data.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSettingsReadTest {
    // firmware 0.7.3+ `status` (patch 0020), after the unlock
    private val lines073 = listOf(
        "n=703 mode=0 interval=60min listen=60s fixto=90s transport=channel check=30s window=30s report_every=3",
        "still=3 still_hb=6h zone_hb=24h owner_hb=8h",
    )

    @Test
    fun parsesSettingsLines() {
        val s = TrackerStatus.parse(lines073)
        assertEquals(60, s.intervalMin)
        assertEquals(60, s.listenS)
        assertEquals("channel", s.transport)
        assertEquals(30, s.checkS)
        assertEquals(30, s.windowS)
        assertEquals(3, s.reportEvery)
        assertEquals(3, s.stillAfter)
        assertEquals(6, s.stillHbH)
        assertEquals(24, s.zoneHbH)
        assertEquals(8, s.ownerHbH)
    }

    @Test
    fun olderFirmwareLacksFields() {
        // 0.6: no check, window, report_every and no still line
        val s = TrackerStatus.parse(listOf("n=17 mode=0 interval=30min listen=180s fixto=90s transport=channel"))
        assertEquals(30, s.intervalMin)
        assertEquals(180, s.listenS)
        assertNull(s.checkS); assertNull(s.windowS); assertNull(s.reportEvery)
        assertNull(s.stillAfter); assertNull(s.stillHbH); assertNull(s.zoneHbH); assertNull(s.ownerHbH)
    }

    @Test
    fun stillZeroIsOff() {
        assertEquals(0, TrackerStatus.parse(listOf("still=0 still_hb=6h zone_hb=24h owner_hb=8h")).stillAfter)
        // still_hb= must not be taken for still=
        assertNull(TrackerStatus.parse(listOf("still_hb=6h")).stillAfter)
    }

    private val app = Tracker(name = "Car", keyHex = "00".repeat(32), intervalMin = 60, reportEvery = 1, checkS = 60, stillAfter = null)

    @Test
    fun adoptsTheTagsSettings() {
        val next = TagSettingsRead.adopt(app, TrackerStatus.parse(lines073), "0.7.4")
        assertEquals(60, next.intervalMin)
        assertEquals(3, next.reportEvery)
        assertEquals(30, next.checkS)
        assertEquals(3, next.stillAfter)
        assertEquals(6, next.stillHbH)
        assertEquals(24, next.zoneHbH)
        assertEquals(8, next.ownerHbH)
        assertEquals("0.7.4", next.firmware)
        assertTrue(TagSettingsRead.settingsDiffer(app, next))
    }

    @Test
    fun sameSettingsDontCount() {
        val same = app.copy(reportEvery = 3, checkS = 30, stillAfter = 3, stillHbH = 6)
        val next = TagSettingsRead.adopt(same, TrackerStatus.parse(lines073), null)
        assertFalse(TagSettingsRead.settingsDiffer(same, next))
        assertEquals(same, next)
    }

    @Test
    fun missingFieldsKeepTheAppsValues() {
        val next = TagSettingsRead.adopt(app, TrackerStatus.parse(listOf("n=17 mode=0 interval=120min listen=180s fixto=90s transport=channel")), null)
        assertEquals(120, next.intervalMin)
        assertEquals(1, next.reportEvery)
        assertEquals(60, next.checkS)
        assertNull(next.stillAfter)
        assertSame(app.firmware, next.firmware)
    }
}

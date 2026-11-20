package app.libretag.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `wzone` console output as firmware 0028 prints it (PROTOCOL.md 6.1); hashes are synthetic. */
class WifiZoneParsersTest {
    @Test fun list() {
        val lines = listOf("wzone list", "WZONE 0 Home 6 AP from 5 scan(s)", "WZONE 1 Cottage 3 AP from 5 scan(s)", "END 2")
        assertEquals(listOf(WifiZoneEntry(0, "Home", 6, 5), WifiZoneEntry(1, "Cottage", 3, 5)), WifiZoneParsers.parseList(lines))
        assertEquals(emptyList<WifiZoneEntry>(), WifiZoneParsers.parseList(listOf("END 0")))
    }

    @Test fun snap() {
        val lines = listOf(
            "SNAP 0 4 AP 3 for zones", "SNAP 1 4 AP 3 for zones", "SNAP 2 3 AP 3 for zones",
            "WZONE 0 Home 3 AP from 3 scan(s)",
            "  1a2b3c4d -65 dBm seen 3/3 spread 1",
            "  5e6f7a8b -83 dBm seen 3/3 spread 1",
            "  9c0d1e2f -91 dBm seen 2/3 spread 2",
            "OK wzone 0",
        )
        assertEquals(SnapStep(1, 4, 3), WifiZoneParsers.parseSnapStep(lines[1]))
        assertNull(WifiZoneParsers.parseSnapStep("WZONE 0 Home 3 AP from 3 scan(s)"))
        val r = WifiZoneParsers.parseSnap(lines)
        assertEquals(WifiZoneEntry(0, "Home", 3, 3), r.zone)
        assertEquals(3, r.steps.size)
        assertNull(r.error)
        val bad = WifiZoneParsers.parseSnap(listOf("SNAP 0 0 AP 0 for zones", "ERR no access point seen in at least half of the scans"))
        assertNull(bad.zone)
        assertTrue(bad.error!!.contains("no access point"))
        assertEquals("ERR no answer", WifiZoneParsers.parseSnap(emptyList()).error)
    }

    @Test fun test() {
        val lines = listOf(
            "TEST 0 4 AP 3 for zones: zone 0 score 95 (was -1)",
            "SCORE 0 0 Home 95 matched 3 of 3",
            "SCORE 0 1 Cottage 0 matched 0 of 4",
            "TEST 1 4 AP 3 for zones: zone -1 score 12 (was 0)",
            "SCORE 1 0 Home 12 matched 1 of 3",
            "END",
        )
        val t = WifiZoneParsers.parseTest(lines)
        assertEquals(2, t.size)
        assertEquals(0, t[0].zone)
        assertEquals(95, t[0].score)
        assertEquals(listOf(ZoneScore(0, "Home", 95, 3, 3), ZoneScore(1, "Cottage", 0, 0, 4)), t[0].scores)
        assertEquals(-1, t[1].zone)
        assertEquals(1, t[1].scores.size)
    }

    @Test fun statusLine() {
        val lines = listOf("n=12 mode=IDLE interval=60min", "wifi zones=2 enter=40 stay=30 min_match=2 scan=active last: 4 AP (3 for zones) in 2421 ms, zone 0 score 95, parked score 100")
        assertEquals(2, WifiZoneParsers.parseZoneCount(lines))
        assertEquals(WifiThresholds(40, 30, 2), WifiZoneParsers.parseThresholds(lines))
        val st = TrackerStatus.parse(lines)
        assertEquals(2, st.wifiZones)
        assertEquals(60, st.intervalMin)
        assertNull(TrackerStatus.parse(listOf("n=12 mode=IDLE interval=60min")).wifiZones)
    }

    @Test fun verdict() {
        val thr = WifiThresholds()
        assertEquals(WifiVerdict.INSIDE, WifiZoneParsers.verdict(ZoneScore(0, "Home", 95, 3, 3), thr))
        assertEquals(WifiVerdict.WEAK, WifiZoneParsers.verdict(ZoneScore(0, "Home", 35, 2, 3), thr))
        assertEquals(WifiVerdict.OUTSIDE, WifiZoneParsers.verdict(ZoneScore(0, "Home", 12, 1, 3), thr))
        // a high score with one network in common is not enough (min_match 2) ...
        assertEquals(WifiVerdict.OUTSIDE, WifiZoneParsers.verdict(ZoneScore(0, "Home", 80, 1, 3), thr))
        // ... unless the zone holds just one
        assertEquals(WifiVerdict.INSIDE, WifiZoneParsers.verdict(ZoneScore(0, "Home", 80, 1, 1), thr))
        assertEquals(WifiVerdict.OUTSIDE, WifiZoneParsers.verdict(null, thr))
    }

    @Test fun commands() {
        assertEquals("wzone snap Home 5", WifiZoneCommands.snap("Home"))
        assertEquals("wzone snap Grandma-s-flat 20", WifiZoneCommands.snap(" Grandma s  flat ", 99))
        assertEquals("wzone test 1", WifiZoneCommands.test())
        assertEquals("wzone del 2", WifiZoneCommands.delete(2))
        // at most 15 bytes of UTF-8, never cut inside a character
        assertEquals("abcdefghijklmno", WifiZoneCommands.cleanName("abcdefghijklmnopq"))
        assertEquals("Záhradaaaaaaaa", WifiZoneCommands.cleanName("Záhradaaaaaaaaa"))
        assertNull(WifiZoneCommands.cleanName("   "))
    }
}

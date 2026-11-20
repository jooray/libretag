package app.libretag.ui

import app.libretag.data.Tracker
import app.libretag.data.WifiZoneRec
import app.libretag.data.Zone
import app.libretag.usb.SnapResult
import app.libretag.usb.SnapStep
import app.libretag.usb.WifiTestScan
import app.libretag.usb.WifiVerdict
import app.libretag.usb.WifiZoneEntry
import app.libretag.usb.WriteResult
import app.libretag.usb.ZoneScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceLogicTest {
    private val home = Zone("Home", 48.70, 21.25, 150)
    private val school = Zone("School", 48.71, 21.26, 100)
    private fun tag(type: String = "thing", vararg zones: Zone, wifi: List<WifiZoneRec> = emptyList()) =
        Tracker(name = "Car", keyHex = "", type = type, zones = zones.toList(), wifiZones = wifi, zonesOnTag = true)

    @Test fun gpsChip() {
        assertEquals(PlaceChip.WRITING, PlaceLogic.gpsChip(onTag = true, onCable = true, writing = true, last = null))
        assertEquals(PlaceChip.ON_TAG, PlaceLogic.gpsChip(true, false, false, null))
        assertEquals(PlaceChip.WAITS_CABLE, PlaceLogic.gpsChip(false, false, false, null))
        assertEquals(PlaceChip.NOT_WRITTEN, PlaceLogic.gpsChip(false, true, false, WriteResult.FAILED))
        assertEquals(PlaceChip.APP_ONLY, PlaceLogic.gpsChip(false, true, false, WriteResult.UNSUPPORTED))
        assertEquals(PlaceChip.APP_ONLY, PlaceLogic.gpsChip(false, false, false, WriteResult.UNSUPPORTED))
    }

    @Test fun autoWriteOnlyOncePerConnection() {
        assertTrue(PlaceLogic.autoWrite(onTag = false, onCable = true, writing = false, last = null, hasPlaces = true))
        assertFalse(PlaceLogic.autoWrite(false, true, true, null, true))
        assertFalse(PlaceLogic.autoWrite(false, true, false, WriteResult.FAILED, true)) // retry is the owner's
        assertFalse(PlaceLogic.autoWrite(false, false, false, null, true))
        assertFalse(PlaceLogic.autoWrite(true, true, false, null, true))
        assertFalse(PlaceLogic.autoWrite(false, true, false, null, false))
    }

    @Test fun names() {
        val taken = listOf("Home", "Garage")
        assertEquals("Cottage", PlaceLogic.cleanName("  Cottage ", taken))
        assertEquals("Old barn", PlaceLogic.cleanName("Old   barn", taken))
        assertNull(PlaceLogic.cleanName("   ", taken))
        assertNull(PlaceLogic.cleanName("home", taken))
        // renaming a place to itself, or only its case, is fine
        assertEquals("Home", PlaceLogic.cleanName("Home", taken, self = "Home"))
        assertEquals("HOME", PlaceLogic.cleanName("HOME", taken, self = "Home"))
        assertEquals("Home", PlaceLogic.suggestName("Home", emptyList()))
        assertEquals("", PlaceLogic.suggestName("Home", listOf("home")))
    }

    @Test fun renameKeepsTagCopyAndDeparture() {
        val t = tag("thing", home, school).copy(leftPlace = "Home", leftAt = 1L)
        val r = PlaceLogic.rename(t, "Home", "Cottage")
        assertEquals(listOf("Cottage", "School"), r.zones.map { it.name })
        assertEquals(home.lat, r.zones[0].lat, 0.0)
        assertTrue(r.zonesOnTag) // the tag keeps only centre and radius
        assertEquals("Cottage", r.leftPlace)
    }

    @Test fun reshapeAddDeleteMarkStale() {
        val t = tag("thing", home)
        val moved = PlaceLogic.reshape(t, "Home", 48.705, 21.255, 300)
        assertFalse(moved.zonesOnTag)
        assertEquals(300, moved.zones[0].radiusM)
        assertEquals(48.705, moved.zones[0].lat, 0.0)
        assertFalse(PlaceLogic.add(t, school).zonesOnTag)
        assertEquals(2, PlaceLogic.add(t, school).zones.size)
        val gone = PlaceLogic.delete(PlaceLogic.add(t, school), "Home")
        assertEquals(listOf("School"), gone.zones.map { it.name })
        assertFalse(gone.zonesOnTag)
    }

    @Test fun notifyChoiceIsExplicitAndPerPlace() {
        val t = tag("vehicle", home, school, wifi = listOf(WifiZoneRec("Garage", 7, 5)))
        assertFalse(PlaceLogic.effective(null, t.type))
        assertTrue(PlaceLogic.effective(null, "person"))
        val r = PlaceLogic.setNotify(PlaceLogic.setNotify(t, "School", true, true), "Garage", false, true)
        assertTrue(r.notifies("School", leave = true))
        assertFalse(r.notifies("Home", leave = true))
        assertTrue(r.notifies("Garage", leave = false))
        assertEquals(true, r.zones[1].notifyLeave)
        assertNull(r.zones[1].notifyArrive)
        assertNull(r.zones[0].notifyLeave)
    }

    @Test fun snapPhases() {
        assertEquals(SnapPhase.LISTEN, PlaceLogic.snapPhase(0, 5, false))
        assertEquals(SnapPhase.LISTEN, PlaceLogic.snapPhase(4, 5, false))
        assertEquals(SnapPhase.SAVE, PlaceLogic.snapPhase(5, 5, false))
        assertEquals(SnapPhase.CHECK, PlaceLogic.snapPhase(5, 5, true))
        // the first scan shows as 1 of 5 from the start, never past the last
        assertEquals(1, PlaceLogic.scanShown(0, 5))
        assertEquals(5, PlaceLogic.scanShown(5, 5))
    }

    private val saved = SnapResult(WifiZoneEntry(0, "Home", 6, 5), listOf(SnapStep(0, 6, 6)), null)
    private fun test(score: Int, matched: Int, of: Int, name: String = "Home") =
        listOf(WifiTestScan(0, 8, 8, 0, score, listOf(ZoneScore(0, name, score, matched, of))))

    @Test fun snapOutcomes() {
        assertEquals(SnapOutcome.OK, PlaceLogic.snapOutcome(saved, test(95, 6, 6)))
        assertEquals(SnapOutcome.WEAK, PlaceLogic.snapOutcome(saved, test(0, 0, 6)))
        assertEquals(SnapOutcome.UNCHECKED, PlaceLogic.snapOutcome(saved, null))
        assertEquals(SnapOutcome.UNCHECKED, PlaceLogic.snapOutcome(saved, test(95, 6, 6, name = "Other")))
        assertEquals(SnapOutcome.NONE, PlaceLogic.snapOutcome(SnapResult(null, emptyList(), "ERR no access point seen in half the scans"), null))
        assertEquals(SnapOutcome.NO_ANSWER, PlaceLogic.snapOutcome(SnapResult(null, emptyList(), "ERR locked"), null))
        assertEquals(SnapOutcome.NO_ANSWER, PlaceLogic.snapOutcome(null, null))
    }

    @Test fun checkHere() {
        assertEquals(WifiVerdict.INSIDE, PlaceLogic.checkVerdict("Home", test(95, 6, 6)))
        assertEquals(WifiVerdict.OUTSIDE, PlaceLogic.checkVerdict("Home", test(0, 0, 6)))
        assertEquals(WifiVerdict.OUTSIDE, PlaceLogic.checkVerdict("Garage", test(95, 6, 6)))
        assertNull(PlaceLogic.checkVerdict("Home", null))
    }

    @Test fun wizardHasFourSteps() {
        assertEquals(4, WizardSteps.COUNT)
        assertFalse(WizardSteps.inFlow(WizardSteps.PLUG))
        assertTrue(WizardSteps.inFlow(WizardSteps.NAME))
        assertTrue(WizardSteps.inFlow(WizardSteps.REST))
        assertFalse(WizardSteps.inFlow(WizardSteps.DONE))
        assertFalse(WizardSteps.pairsNow(WizardSteps.REST, 0)) // Skip
        assertTrue(WizardSteps.pairsNow(WizardSteps.REST, 1)) // Pair
    }
}

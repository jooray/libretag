package app.libretag.data

import app.libretag.proto.Mode
import app.libretag.proto.Report
import app.libretag.proto.WifiFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Entering and leaving a Wi-Fi zone from the quiet flags of a tag without GPS. */
class WifiZoneTransitTest {
    private fun rep(quiet: Boolean = false, owner: Boolean = false, parked: Boolean = false, fix: Boolean = false, mode: Mode = Mode.IDLE,
                    wifi: Boolean = false) =
        Report(hasFix = fix, fixTime = if (fix) 1_800_000_000 else 0, lat = 0.0, lon = 0.0, haccM = null, batteryPct = 80, mode = mode,
            cmdAck = false, cmdSeq = 0, lowBatt = false, nbTotal = 0, nbDiscover = false, neighbours = emptyList(),
            quiet = quiet, quietOwner = owner, parked = parked,
            wifi = if (wifi) WifiFields(ByteArray(6) { 2 }, -60, 5, null, -95) else null)

    private val W = WifiZoneTransit

    @Test fun entersOnAQuietZoneReportWithoutAFix() {
        assertEquals(WifiZoneTransit.Step("Home", true), W.step(null, false, rep(quiet = true), true, true, "Home"))
        assertEquals(WifiZoneTransit.Step(W.UNNAMED, true), W.step(null, false, rep(quiet = true), true, true, null))
        // the heartbeat while inside keeps it inside
        assertEquals(WifiZoneTransit.Step("Home", true), W.step("Home", true, rep(quiet = true), true, true, "Home"))
    }

    @Test fun leavesAtTheNextNormalReport() {
        assertEquals(WifiZoneTransit.Step(null, false, "Home"), W.step("Home", true, rep(), true, true, "Home"))
        // parked means outside any zone (a zone wins over parked)
        assertEquals(WifiZoneTransit.Step(null, false, "Home"), W.step("Home", true, rep(quiet = true, parked = true), true, true, "Home"))
    }

    @Test fun aReportAfterARebootIsNotLeavingWithoutAScan() {
        // n jumped (a reset moves the counter to the state's checkpoint) and no Wi-Fi in it: stays in
        assertEquals(WifiZoneTransit.Step("Home", true), W.step("Home", true, rep(), true, true, "Home", n = 771, lastN = 760))
        // the next report in sequence decides
        assertEquals(WifiZoneTransit.Step(null, false, "Home"), W.step("Home", true, rep(), true, true, "Home", n = 772, lastN = 771))
        // a jump with a Wi-Fi scan that matched no zone: the tag is elsewhere
        assertEquals(WifiZoneTransit.Step(null, false, "Home"), W.step("Home", true, rep(wifi = true), true, true, "Home", n = 771, lastN = 760))
        // a quiet zone report after the reboot (firmware 0.7.8) keeps it in
        assertEquals(WifiZoneTransit.Step("Home", true), W.step("Home", true, rep(quiet = true), true, true, "Home", n = 771, lastN = 760))
    }

    @Test fun leavingWithTheOwnersDeviceIsNoAlarm() {
        assertEquals(WifiZoneTransit.Step(null, false, null), W.step("Home", true, rep(quiet = true, owner = true), true, true, "Home"))
    }

    @Test fun saysNothingWhereTheRuleDoesNotApply() {
        assertNull(W.step("Home", true, rep(), false, true, "Home"))                 // an old report
        assertNull(W.step("Home", true, rep(mode = Mode.TRACK), true, true, "Home"))  // Track reports anywhere
        assertNull(W.step("Home", true, rep(fix = true), true, true, "Home"))        // a fix: the GPS rule
        assertNull(W.step(null, false, rep(quiet = true), true, false, null))        // a GPS tag
        assertNull(W.step("Home", false, rep(), true, true, "Home"))                 // inside a GPS zone
    }

    @Test fun statusNamesTheWifiZone() {
        val p = Position(fixTime = 0, receivedAt = 1, hasFix = false, lat = 0.0, lon = 0.0, altM = 0, battery = 80, mode = "IDLE",
            hops = 0, snrDb = 0.0, n = 1, quiet = true, quietReason = "zone")
        val one = Tracker(name = "T", keyHex = "00", wifiTag = true, wifiZones = listOf(WifiZoneRec("Home", 6, 5)))
        assertEquals("Home", Status.quietZoneName(one, p))
        val two = one.copy(wifiZones = one.wifiZones + WifiZoneRec("Cottage", 3, 5))
        assertEquals(W.UNNAMED, Status.quietZoneName(two, p))
        // a GPS tag without a fix: unknown (Home)
        assertNull(Status.quietZoneName(Tracker(name = "T", keyHex = "00"), p))
    }
}

package app.libretag.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceNotifyTest {
    private val home = Zone("Home", 48.70, 21.25, 150)
    private val school = Zone("School", 48.71, 21.26, 100)
    private val t0 = 1_800_000_000_000L

    private fun tag(type: String = "thing", vararg zones: Zone, alerts: AlertPrefs = AlertPrefs(), alarm: String? = null) =
        Tracker(name = "T", keyHex = "", type = type, zones = zones.toList(), alerts = alerts, alarm = alarm)

    private fun fix(lat: Double, lon: Double, at: Long) =
        Position(fixTime = at / 1000, receivedAt = at, hasFix = true, lat = lat, lon = lon, altM = 0, battery = 80, mode = "IDLE", hops = 0, snrDb = 0.0)

    @Test fun defaultsByType() {
        assertTrue(tag("person", home).notifies("Home", leave = true))
        assertTrue(tag("person", home).notifies("Home", leave = false))
        for (type in listOf("thing", "vehicle")) {
            assertFalse(tag(type, home).notifies("Home", leave = true))
            assertFalse(tag(type, home).notifies("Home", leave = false))
        }
    }

    @Test fun explicitChoiceSticksAcrossTypeChange() {
        val t = tag("vehicle", home.copy(notifyLeave = true), school)
        assertTrue(t.notifies("Home", true))
        assertFalse(t.notifies("Home", false)) // arrive follows the type
        val asPerson = t.copy(type = "person")
        assertTrue(asPerson.notifies("School", true)) // null moved with the type
        assertTrue(asPerson.copy(zones = listOf(home.copy(notifyArrive = false))).notifies("Home", true))
        assertFalse(asPerson.copy(zones = listOf(home.copy(notifyArrive = false))).notifies("Home", false))
    }

    @Test fun unknownPlaceFollowsType() {
        assertTrue(tag("person").notifies("Gone", true))
        assertFalse(tag("thing").notifies("Gone", true))
    }

    @Test fun unnamedWifiPlaceNotifiesWhenAnyDoes() {
        val base = tag("thing").copy(wifiZones = listOf(WifiZoneRec("A", 5, 3), WifiZoneRec("B", 4, 3)))
        assertFalse(base.notifies(WifiZoneTransit.UNNAMED, true))
        val one = base.copy(wifiZones = listOf(WifiZoneRec("A", 5, 3), WifiZoneRec("B", 4, 3, notifyLeave = true)))
        assertTrue(one.notifies(WifiZoneTransit.UNNAMED, true))
        assertFalse(one.notifies(WifiZoneTransit.UNNAMED, false))
        assertTrue(one.notifies("B", true))
        // a person's Wi-Fi places all off
        val off = one.copy(type = "person", wifiZones = listOf(WifiZoneRec("A", 5, 3, notifyLeave = false)))
        assertFalse(off.notifies(WifiZoneTransit.UNNAMED, true))
        assertTrue(off.notifies(WifiZoneTransit.UNNAMED, false))
    }

    @Test fun zoneAlarmBecomesLeftEvent() {
        val m = Places.migrate(tag("thing", home, alarm = "zone:Home:$t0"))
        assertNull(m.alarm)
        assertEquals("Home", m.leftPlace)
        assertEquals(t0, m.leftAt)
        assertEquals(Places.VERSION, m.placesV)
    }

    @Test fun zoneAlarmNameWithColonAndUnnamed() {
        assertEquals("Café: back" to t0, Places.parseZoneAlarm("zone:Café: back:$t0"))
        assertEquals(WifiZoneTransit.UNNAMED to t0, Places.parseZoneAlarm("zone:${WifiZoneTransit.UNNAMED}:$t0"))
        assertNull(Places.parseZoneAlarm("guard:$t0"))
        assertNull(Places.parseZoneAlarm(null))
    }

    @Test fun guardAlarmIsKept() {
        val m = Places.migrate(tag(alarm = "guard:$t0"))
        assertEquals("guard:$t0", m.alarm)
        assertTrue(m.guardAlarm)
        assertNull(m.leftPlace)
    }

    @Test fun leftZoneOffBecomesNotifyLeaveFalseOnce() {
        val t = tag("person", home, school.copy(notifyLeave = true), alerts = AlertPrefs(leftZone = false))
            .copy(wifiZones = listOf(WifiZoneRec("Flat", 6, 3)))
        val m = Places.migrate(t)
        assertEquals(false, m.zones[0].notifyLeave)
        assertEquals(true, m.zones[1].notifyLeave) // an explicit choice is not overwritten
        assertNull(m.zones[0].notifyArrive)
        assertEquals(false, m.wifiZones[0].notifyLeave)
        assertFalse(m.notifies("Home", true))
        assertTrue(m.notifies("Home", false))
        // the user turns it on later: the migration doesn't run again
        val on = m.copy(zones = m.zones.map { it.copy(notifyLeave = true) })
        assertSame(on, Places.migrate(on))
    }

    @Test fun leftZoneOnChangesNothingButTheVersion() {
        val t = tag("thing", home)
        val m = Places.migrate(t)
        assertEquals(t.zones, m.zones)
        assertEquals(Places.VERSION, m.placesV)
        assertSame(m, Places.migrate(m))
    }

    @Test fun migrationSurvivesJson() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }
        // a 0.8 tag as stored: no placesV, a latched zone alarm, "Left a zone" off
        val old = """{"name":"Car","keyHex":"","type":"vehicle","zones":[{"name":"Home","lat":48.7,"lon":21.25,"radiusM":150}],""" +
            """"alerts":{"leftZone":false},"alarm":"zone:Home:$t0"}"""
        val m = Places.migrate(json.decodeFromString(Tracker.serializer(), old))
        assertEquals("Home", m.leftPlace)
        assertEquals(false, m.zones[0].notifyLeave)
        val again = json.decodeFromString(Tracker.serializer(), json.encodeToString(Tracker.serializer(), m))
        assertEquals(m, again)
        assertSame(again, Places.migrate(again))
    }

    @Test fun carryNotifyKeepsChoicesByName() {
        val old = listOf(WifiZoneRec("A", 5, 3, notifyLeave = true), WifiZoneRec("B", 4, 3, notifyArrive = false))
        val fresh = listOf(WifiZoneRec("A", 7, 4), WifiZoneRec("C", 3, 3))
        val out = Places.carryNotify(old, fresh)
        assertEquals(WifiZoneRec("A", 7, 4, notifyLeave = true), out[0])
        assertEquals(WifiZoneRec("C", 3, 3), out[1])
    }

    @Test fun leftRecently() {
        val t = tag().copy(leftPlace = "Home", leftAt = t0)
        assertTrue(t.leftRecently(t0 + 3600_000L))
        assertFalse(t.leftRecently(t0 + Places.LEFT_SHOW_MS))
        assertFalse(t.copy(inZone = "School").leftRecently(t0 + 60_000L))
        assertFalse(tag().leftRecently(t0))
    }

    @Test fun departureIsTheNeutralLeftState() {
        val now = t0 + 60_000L
        val t = tag("thing", home).copy(leftPlace = "Home", leftAt = t0, positions = listOf(fix(48.80, 21.30, t0)), lastN = 1)
        val s = Status.of(t, now)
        assertEquals(StatusKind.LEFT, s.kind)
        assertEquals("Home", s.leftPlace)
        assertEquals(t0, s.leftAt)
        assertEquals(TagStatus.Level.NONE, s.level)
        assertFalse(s.needsLook)
        // whatever the old "Left a zone" switch says: it is where the tag is, not an alarm
        assertEquals(StatusKind.LEFT, Status.of(t.copy(alerts = AlertPrefs(leftZone = false)), now).kind)
        // after 12 h it is an ordinary report again
        val later = t0 + Places.LEFT_SHOW_MS
        assertEquals(StatusKind.OK, Status.of(t.copy(positions = listOf(fix(48.80, 21.30, later))), later + 60_000L).kind)
    }

    @Test fun guardNewSpotAndStop() {
        val p1 = fix(48.70, 21.25, t0)
        val p2 = fix(48.72, 21.27, t0 + 600_000L)
        val armed = tag().copy(positions = listOf(p1)).guardHere(t0)
        assertTrue(armed.guarding)
        assertEquals(48.70, armed.guardLat!!, 0.0)
        val fired = armed.copy(positions = listOf(p1, p2), guardAlerted = true, alarm = Places.guardAlarm(p2.receivedAt))
        assertTrue(fired.guardAlarm)
        val rearmed = fired.guardHere(t0 + 700_000L)
        assertFalse(rearmed.guardAlarm)
        assertFalse(rearmed.guardAlerted)
        assertEquals(48.72, rearmed.guardLat!!, 0.0)
        assertEquals((t0 + 700_000L) / 1000, rearmed.guardSince)
        val stopped = fired.guardOff()
        assertFalse(stopped.guarding)
        assertFalse(stopped.guardAlarm)
        assertFalse(stopped.guardAlerted)
    }

    @Test fun wifiAutoPrefMigration() {
        assertEquals(AppPrefs.WIFI_AUTO_TAP, AppPrefs.resolveWifiAuto(null, false))
        assertEquals(AppPrefs.WIFI_AUTO_LOST, AppPrefs.resolveWifiAuto(null, true))
        assertEquals(AppPrefs.WIFI_AUTO_ALWAYS, AppPrefs.resolveWifiAuto("always", false))
        assertEquals(AppPrefs.WIFI_AUTO_TAP, AppPrefs.resolveWifiAuto("tap", true))
        assertEquals(AppPrefs.WIFI_AUTO_LOST, AppPrefs.resolveWifiAuto("garbage", true))
    }
}

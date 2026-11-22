package app.libretag.data

import app.libretag.data.Diagnosis.Cause
import app.libretag.data.Diagnosis.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/tag-diagnosis.md 5: one case per cause, the order, and that a guess never lowers the level. */
class DiagnosisTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val h = 3_600_000L

    private fun pos(agoMin: Long, n: Long = 100, batt: Int? = 80, fix: Boolean = true, quiet: Boolean = false, via: String = "nostr",
                    nb: Int = 2, lat: Double = 48.70, lon: Double = 21.25, nextInS: Long? = null, mode: String = "IDLE") = Position(
        fixTime = 0, receivedAt = now - agoMin * min, hasFix = fix, lat = lat, lon = lon, altM = 0, battery = batt,
        mode = mode, hops = null, snrDb = 0.0, via = via, n = n, quiet = quiet, nbTotal = nb, nextInS = nextInS,
        neighbours = if (nb > 0) listOf(NeighbourRec("a5e1", 5.0)) else emptyList())

    /** hourly, heard 3 h ago: SILENT */
    private fun silent(vararg extra: Position) = Tracker(name = "Car", keyHex = "00".repeat(32), intervalMin = 60, lastN = 100,
        positions = extra.toList().ifEmpty { listOf(pos(99, n = 99), pos(180)) }, firmware = "0.9.5")

    private val ok = DiagContext()

    private fun diag(t: Tracker, c: DiagContext = ok, at: Long = now) = Diagnosis.of(t, Status.of(t, at), c, at)
    private fun cause(t: Tracker, c: DiagContext = ok) = diag(t, c)?.cause

    @Test fun nothingInsideTheScheduleOrAQuietHeartbeat() {
        assertNull(diag(silent(pos(20))))
        assertNull(diag(silent(pos(5 * 60, quiet = true))))
    }

    @Test fun unknownWhenNothingExplains() {
        val d = diag(silent())!!
        assertEquals(Cause.UNKNOWN, d.cause)
        assertEquals(Level.WARN, d.level)
    }

    @Test fun cableCauses() {
        assertEquals(Cause.BOOTLOADER, cause(silent(), ok.copy(cable = Cable.BOOTLOADER)))
        assertEquals(Cause.ESP_DOWNLOAD, cause(silent(), ok.copy(cable = Cable.ESP_DOWNLOAD)))
        assertEquals(Cause.ON_CABLE, cause(silent(), ok.copy(cable = Cable.TAG)))
        assertEquals(Level.CALM, diag(silent(), ok.copy(cable = Cable.TAG))!!.level)
    }

    @Test fun healthCauses() {
        val fresh = TagHealth(at = now - min, n = 101, resets = 4, lastReset = "brownout", bootDelayS = 960, txOk = true)
        assertEquals(Cause.RESET_LOOP, cause(silent().copy(health = fresh)))
        assertEquals(Cause.TX_FLOOR, cause(silent().copy(health = fresh.copy(txOk = false))))
        // an old health reading says nothing
        assertEquals(Cause.UNKNOWN, cause(silent().copy(health = fresh.copy(at = now - 2 * h))))
        // the counter far ahead
        assertEquals(Cause.COUNTER_AHEAD, cause(silent().copy(health = fresh.copy(resets = 0, n = 5000))))
    }

    @Test fun firmwareTooOld() {
        assertEquals(Cause.FIRMWARE_TOO_OLD, cause(silent().copy(firmware = "0.9.4")))
    }

    @Test fun blindness() {
        assertEquals(Cause.PHONE_OFFLINE, cause(silent(), ok.copy(online = false)))
        assertEquals(Cause.RELAYS_DOWN, cause(silent(), ok.copy(relaysUp = 0, relaysAll = 3)))
        // the own node keeps the phone seeing
        assertEquals(Cause.UNKNOWN, cause(silent(), ok.copy(online = false, mesh = MeshLink.UP)))
        // back, but the missed reports are not fetched yet
        assertEquals(Cause.JUST_BACK, cause(silent(), ok.copy(pathUpSince = null, blindFrom = now - 2 * h)))
        // fetched a while ago and still nothing: not the phone's fault any more
        assertEquals(Cause.UNKNOWN, cause(silent(), ok.copy(pathUpSince = now - 10 * min, backfillAt = now - 10 * min)))
        // the backfill came back a minute ago: nothing is being looked for any more
        assertEquals(Cause.UNKNOWN, cause(silent(), ok.copy(pathUpSince = now - min, backfillAt = now - min)))
        // blind with no link: the backfill can't run, so no "checking"
        assertFalse(Diagnosis.justBack(ok.copy(linkUp = false, pathUpSince = null), now - h))
        // the link is back, the backfill not done: checking, whatever the last backfill was
        assertTrue(Diagnosis.justBack(ok.copy(pathUpSince = null, backfillAt = now - 3 * h), now - h))
        assertEquals(Cause.JUST_BACK, cause(silent(), ok.copy(pathUpSince = null, backfillAt = now - 3 * h, blindFrom = now - 2 * h)))
    }

    @Test fun notDelivered() {
        val r = ReceiptSeen(now - 30 * min, 101, "1-3", "wss://relay.example", trusted = true)
        val d = diag(silent(), ok.copy(receipt = r))!!
        assertEquals(Cause.NOT_DELIVERED, d.cause)
        assertTrue(Diagnosis.Action.ADD_RELAY in d.actions)
        // a bridge with receipts on one day only: no Add relay
        assertFalse(Diagnosis.Action.ADD_RELAY in diag(silent(), ok.copy(receipt = r.copy(trusted = false)))!!.actions)
    }

    @Test fun withYouAndItsExpiry() {
        val t = silent().copy(bleSight = BleSightRec(now - 10 * min, "here", -60))
        assertEquals(Cause.WITH_YOU, cause(t))
        assertEquals(Level.CALM, diag(t)!!.level)
        // the owner heartbeat (8 h) + 30 min after the sighting: the next cause
        val old = silent(pos(99, n = 99), pos(10 * 60)).copy(bleSight = BleSightRec(now - 9 * h, "here", -60))
        assertEquals(Cause.UNKNOWN, cause(old))
        // the console said quiet = owner
        assertEquals(Cause.WITH_YOU, cause(silent().copy(health = TagHealth(at = now - 5 * min, quiet = "owner"))))
    }

    @Test fun restarts() {
        assertEquals(Cause.RESTARTED_HERE, cause(silent().copy(bleBoots = listOf(now - 5 * min))))
        val t = silent().copy(bleBoots = listOf(now - 40 * min, now - 5 * min))
        assertEquals(Cause.RESTARTING, cause(t))
        assertEquals(Diagnosis.Confidence.MEDIUM, diag(t)!!.confidence)
        val boot = silent().copy(bootRecs = listOf(BootRec(now - 2 * h, 96, "brownout", 3)))
        assertEquals(Cause.RESTARTING, cause(boot))
        assertEquals(Diagnosis.Confidence.HIGH, diag(boot)!!.confidence)
        // one power-on boot explains a gap, no alarm
        assertEquals(Cause.UNKNOWN, cause(silent().copy(bootRecs = listOf(BootRec(now - 2 * h, 96, "power", 0)))))
    }

    @Test fun updateUnfinished() {
        assertEquals(Cause.UPDATE_UNFINISHED, cause(silent().copy(dfuStartedAt = now - h)))
        // with the bootloader on the cable it is the bootloader
        assertEquals(Cause.BOOTLOADER, cause(silent().copy(dfuStartedAt = now - h), ok.copy(cable = Cable.BOOTLOADER)))
    }

    @Test fun batteryEmpty() {
        assertEquals(Cause.BATTERY_EMPTY, cause(silent(pos(99, n = 99), pos(180, batt = 4))))
        assertEquals(Cause.BATTERY_EMPTY, cause(silent().copy(critBattNotified = true)))
    }

    @Test fun schedule() {
        // the tag says it reports every 2 h while the app has 1 h
        val h2 = TagHealth(at = now - 5 * min, intervalMin = 60, reportEvery = 2)
        assertEquals(Cause.SCHEDULE_MISMATCH, cause(silent().copy(health = h2)))
        assertEquals(Cause.SCHEDULE_MISMATCH, cause(silent().copy(cableDirty = true)))
        // a SET_IDLE to 2 h on its way
        val cmd = app.libretag.proto.Command.setIdle(5, 120)
        val p = PendingCommand(5, "", app.libretag.proto.LibreTag.bytesToHex(cmd.encode()), kind = "INTERVAL")
        val late = silent(pos(130))
        assertEquals(Cause.SCHEDULE_PENDING, cause(late.copy(pending = p)))
        assertEquals(Level.NOTE, diag(late.copy(pending = p))!!.level)
        // past the new period + margin: no longer an explanation
        assertEquals(Cause.UNKNOWN, cause(silent().copy(pending = p)))
    }

    @Test fun outOfRangeAndNodeDown() {
        assertEquals(Cause.OUT_OF_RANGE, cause(silent(pos(99, n = 99, nb = 0), pos(180, nb = 0))))
        val mesh = silent(*(0 until 5).map { pos(180L + (4 - it) * 60, n = 96L + it, via = "mesh") }.toTypedArray())
        assertEquals(Cause.NODE_DOWN, cause(mesh, ok.copy(mesh = MeshLink.DOWN, meshUpAt = now - 3 * h)))
    }

    @Test fun guessesNeverLowerTheLevel() {
        val known = silent().copy(knownDevices = listOf(KnownDevice("aa:bb:cc:dd:ee:ff")), knownOnTag = true)
        val d = diag(known)!!
        assertEquals(Cause.MAYBE_WITH_YOU, d.cause)
        assertEquals(Level.WARN, d.level)
        assertTrue(Diagnosis.Action.CHECK_HERE in d.actions)
        // "not here" removes the guess
        assertEquals(Cause.UNKNOWN, cause(known.copy(notHereAt = now - 10 * min)))
        // a person's tag is never "quiet near you"
        assertEquals(Cause.UNKNOWN, cause(known.copy(type = "person")))
        val home = silent().copy(zones = listOf(Zone("Home", 48.70, 21.25, 150)))
        assertEquals(Cause.MAYBE_QUIET_PLACE, cause(home))
        assertEquals(Level.WARN, diag(home)!!.level)
        assertEquals(Cause.AFTER_CABLE, cause(silent().copy(lastUsbAt = now - 2 * h)))
        assertEquals(Level.WARN, diag(silent().copy(lastUsbAt = now - 2 * h))!!.level)
    }

    @Test fun phoneClock() {
        assertEquals(Cause.PHONE_CLOCK, cause(silent(), ok.copy(clockSkewMs = -7 * min)))
        assertEquals(Cause.UNKNOWN, cause(silent(), ok.copy(clockSkewMs = -30_000)))
    }

    @Test fun order() {
        val all = silent().copy(bleSight = BleSightRec(now - 10 * min, "here", -60), critBattNotified = true)
        // bootloader beats offline beats with-you beats battery
        assertEquals(Cause.BOOTLOADER, cause(all, ok.copy(cable = Cable.BOOTLOADER, online = false)))
        assertEquals(Cause.PHONE_OFFLINE, cause(all, ok.copy(online = false)))
        assertEquals(Cause.WITH_YOU, cause(all))
        assertEquals(Cause.BATTERY_EMPTY, cause(all.copy(bleSight = null)))
    }

    @Test fun shownAndBand() {
        val late = silent(pos(70))
        val s = Status.of(late, now)
        assertEquals(StatusKind.OK, s.kind)
        // NOTE shows as soon as late, WARN waits for SILENT
        assertTrue(Diagnosis.shown(diag(late, ok.copy(online = false)), s))
        assertFalse(Diagnosis.shown(diag(late), s))
        val sil = Status.of(silent(), now)
        assertFalse(Diagnosis.band(diag(silent(), ok.copy(online = false)), sil))
        assertTrue(Diagnosis.band(diag(silent()), sil))
        assertTrue(Diagnosis.band(null, sil))
    }

    @Test fun checklistHasEveryRow() {
        val rows = Diagnosis.checklist(silent(), ok, now, "0.9.4")
        assertEquals(Diagnosis.Check.entries.toList(), rows.map { it.check })
    }

    @Test fun findings() {
        val cliff = silent(pos(70, n = 99, batt = 80), pos(10, batt = 50))
        assertTrue(Diagnosis.findings(cliff, ok, now).any { it.finding == Diagnosis.Finding.BATTERY_CLIFF })
        val nofix = silent(pos(240, n = 96), pos(180, n = 97, fix = false), pos(120, n = 98, fix = false), pos(60, n = 99, fix = false))
        assertTrue(Diagnosis.findings(nofix, ok, now).any { it.finding == Diagnosis.Finding.NO_GPS_RUN })
        val old = silent().copy(firmware = "0.9.5")
        assertTrue(Diagnosis.findings(old, ok, now, "0.9.6").any { it.finding == Diagnosis.Finding.FIRMWARE_BEHIND })
        val cap = silent(pos(5, mode = "TRACK", nextInS = 3600)).copy(modeIntervalS = 300)
        assertTrue(Diagnosis.findings(cap, ok, now).any { it.finding == Diagnosis.Finding.FLOOD_CAP })
    }
}

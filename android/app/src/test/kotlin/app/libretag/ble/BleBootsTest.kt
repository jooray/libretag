package app.libretag.ble

import app.libretag.data.CommandResult
import app.libretag.data.PendingCommand
import app.libretag.data.Position
import app.libretag.data.Tracker
import app.libretag.proto.BleCommand
import app.libretag.proto.Command
import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BleBootsTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val t = Tracker(name = "Car", keyHex = "00")

    @Test fun newSaltIsANewWindow() {
        val a = BleBoots.onAdvert(t, "console", "aabbccddeeff", -60, now)
        assertEquals(listOf(now), a.bleBoots)
        // the same window again a few seconds later: nothing changes
        assertSame(a, BleBoots.onAdvert(a, "console", "aabbccddeeff", -55, now + 5_000))
        val b = BleBoots.onAdvert(a, "console", "112233445566", -60, now + 20 * min)
        assertEquals(listOf(now, now + 20 * min), b.bleBoots)
    }

    @Test fun ownWindowExcluded() {
        val asked = t.copy(pending = PendingCommand(3, "", "00", kind = "BLE_CONSOLE", queuedAt = now - 10 * min))
        assertTrue(BleBoots.onAdvert(asked, "console", "aabbccddeeff", -60, now).bleBoots.isEmpty())
        val confirmed = t.copy(lastCommand = CommandResult("BLE_CONSOLE", 3, now - 5 * min))
        assertTrue(BleBoots.onAdvert(confirmed, "console", "aabbccddeeff", -60, now).bleBoots.isEmpty())
        // confirmed long ago: a restart again
        val old = t.copy(lastCommand = CommandResult("BLE_CONSOLE", 3, now - 60 * min))
        assertEquals(1, BleBoots.onAdvert(old, "console", "aabbccddeeff", -60, now).bleBoots.size)
    }

    @Test fun twoInAnHourIsRestarting() {
        var x = BleBoots.onAdvert(t.copy(positions = listOf(pos(now - 3 * 60 * min))), "console", "000000000001", -60, now - 40 * min)
        x = BleBoots.onAdvert(x, "console", "000000000002", -60, now - 5 * min)
        assertEquals(app.libretag.data.Diagnosis.Cause.RESTARTING, app.libretag.data.Diagnosis.restarting(x, now)?.cause)
    }

    @Test fun notHere() {
        val asking = t.copy(hereAskAt = now - 3 * min)
        assertTrue(BleBoots.notHere(asking, now))
        assertFalse(BleBoots.notHere(asking, now - 2 * min))
        // the advert came: answered
        val seen = BleBoots.onAdvert(asking, "console", "aabbccddeeff", -60, now - 3 * min)
        assertFalse(BleBoots.notHere(seen, now))
    }

    private fun pos(at: Long) = Position(fixTime = 0, receivedAt = at, hasFix = false, lat = 0.0, lon = 0.0, altM = 0,
        battery = 80, mode = "IDLE", hops = 0, snrDb = 0.0, n = 4)
}

/** The n + 1 sealings while a tag is late (tag-diagnosis 4.1), against the `checks` vectors. */
class BleCommandAheadTest {
    private val v = Json.parseToJsonElement(File("../../host/tests/vectors/lt2.json").readText()).jsonObject
    private val now = 1_800_000_000_000L

    @Test fun candidatesAroundNow() {
        val keys = Keys(LibreTag.hexToBytes(v["k0"]!!.jsonPrimitive.content)!!)
        val c = v["checks"]!!.jsonArray.first().jsonObject
        val n = c["n"]!!.jsonPrimitive.int.toLong()
        val baseMin = (c["base_s"]!!.jsonPrimitive.long / 60).toInt()
        val hb = c["heartbeat_s"]!!.jsonPrimitive.long
        val offs = LibreTag.checkOffsets(keys.cmd(LibreTag.epoch(n)), n, baseMin, hb)
        assertEquals(c["before_heartbeat"]!!.jsonArray.map { it.jsonPrimitive.long }, offs)
        val cmd = Command.buzz(9, 2)
        val t = Tracker(name = "Car", keyHex = "00", lastN = n - 1, intervalMin = baseMin, firmware = "0.9.4",
            pending = PendingCommand(9, "", LibreTag.bytesToHex(cmd.encode()), kind = "BEEP", queuedAt = now - 60_000L))
        val t0 = now - offs[2] * 1000L // check 3 is now
        val list = BleBeaconPlan.ahead(t, t0, now) { if (it == n) offs else null }
        assertEquals(listOf(n to BleCommand.K_NEXT, n to 2, n to 3, n to 4), list.map { it.n to it.k })
        // without a known t0 only k = K_NEXT
        assertEquals(listOf(n to BleCommand.K_NEXT), BleBeaconPlan.ahead(t, null, now) { offs }.map { it.n to it.k })
        // a shared tag (interval unknown): K_NEXT only
        assertEquals(1, BleBeaconPlan.ahead(t.copy(sharedBy = "x", sharedControl = true), t0, now) { offs }.size)
        // nothing pending: nothing
        assertTrue(BleBeaconPlan.ahead(t.copy(pending = null), t0, now) { offs }.isEmpty())
    }
}

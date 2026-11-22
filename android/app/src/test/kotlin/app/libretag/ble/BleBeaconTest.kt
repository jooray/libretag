package app.libretag.ble

import app.libretag.data.CheckPlan
import app.libretag.data.KnownDevice
import app.libretag.data.PendingCommand
import app.libretag.data.Position
import app.libretag.data.Tracker
import app.libretag.proto.BleCommand
import app.libretag.proto.Command
import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Commands over Bluetooth advertising (PROTOCOL.md 3.1): payload vectors and which sealing is on air when. */
class BleBeaconTest {
    private val v = Json.parseToJsonElement(File("../../host/tests/vectors/lt2.json").readText()).jsonObject
    private fun hex(s: String) = LibreTag.hexToBytes(s)!!

    @Test fun payloadVectors() {
        val keys = Keys(hex(v["k0"]!!.jsonPrimitive.content))
        for (e in v["ble_commands"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.int.toLong()
            val k = o["k"]!!.jsonPrimitive.int
            val cmd = Command.decode(hex(o["pt"]!!.jsonPrimitive.content))!!
            val adv = BleCommand.payload(keys.cmd(LibreTag.epoch(n)), n, cmd, k)!!
            assertArrayEquals(hex(o["adv"]!!.jsonPrimitive.content), adv)
            assertEquals(BleCommand.LEN, adv.size)
        }
    }

    @Test fun longCommandIsMeshOnly() {
        val keys = Keys(ByteArray(32))
        val c = Command(1, Command.OP_SET_IDLE, ByteArray(6) + byteArrayOf(1))
        assertNull(BleCommand.payload(keys.cmd(0), 5, c, 1))
    }

    private val now = 1_800_000_000_000L

    private fun pos(n: Long, quiet: Boolean, agoS: Long) = Position(
        fixTime = 0, receivedAt = now - agoS * 1000, hasFix = false, lat = 0.0, lon = 0.0, altM = 0, battery = 80,
        mode = "IDLE", hops = 0, snrDb = 0.0, n = n, quiet = quiet, quietReason = if (quiet) "owner" else null,
        nextInS = 8 * 3600L, checks = true)

    private fun tag(p: Position, fw: String? = "0.9.5", known: Boolean = true, queuedAgoS: Long = 60, kind: String = "INTERVAL") = Tracker(
        name = "Car", keyHex = "00", positions = listOf(p), lastN = p.n, firmware = fw,
        knownDevices = if (known) listOf(KnownDevice("phone", kind = "irk")) else emptyList(),
        pending = PendingCommand(5, kind.lowercase(), "22", kind = kind, queuedAt = now - queuedAgoS * 1000))

    @Test fun quietTagGetsTheNextCheckThenTheHeartbeat() {
        val p = pos(40, true, 3000)
        val plan = CheckPlan(40, p.receivedAt, 60, listOf(3500L, 7300L))
        val t = tag(p)
        // check 1 is 500 s ahead
        val a = BleBeaconPlan.target(t, plan, now)!!
        assertEquals(40L, a.n); assertEquals(1, a.k)
        assertEquals(p.receivedAt + 3500_000L + BleBeaconPlan.guardMs(3500) + BleBeaconPlan.SCAN_AFTER_MS, a.untilMs)
        // just after check 1's scan: check 2
        assertEquals(2, BleBeaconPlan.target(t, plan, a.untilMs!! + 1)!!.k)
        // after the last check: the heartbeat, not a check
        val late = p.receivedAt + 7400_000L + 60_000L
        assertEquals(BleCommand.K_NEXT, BleBeaconPlan.target(t, plan, late)!!.k)
    }

    @Test fun reportEveryRIsCheckR() {
        val p = pos(41, false, 100)
        val plan = CheckPlan(41, p.receivedAt, 60, listOf(3500L, 7300L)) // report_every 3: wake-up 3 reports
        assertEquals(3, BleBeaconPlan.target(tag(p), plan, p.receivedAt + 8000_000L)!!.k)
    }

    @Test fun normalReportingIsTheNextWakeUp() {
        val p = pos(42, false, 100)
        assertEquals(BleCommand.K_NEXT, BleBeaconPlan.target(tag(p), null, now)!!.k)
        // a plan of an older report does not count
        assertEquals(BleCommand.K_NEXT, BleBeaconPlan.target(tag(p), CheckPlan(41, 0, 60, listOf(1L)), now)!!.k)
    }

    @Test fun aTagWithoutKnownDevicesScansToo() {
        val p = pos(43, false, 100)
        val plan = CheckPlan(43, p.receivedAt, 60, listOf(3500L, 7300L))
        val t = tag(p, known = false)
        assertTrue(BleBeaconPlan.eligible(t))
        assertTrue(BleBeaconPlan.eligible(tag(p, fw = null)))
        // the listen-only check 1 between reports gets its own sealing
        assertEquals(1, BleBeaconPlan.target(t, plan, p.receivedAt + 1000)!!.k)
    }

        @Test fun notAdvertised() {
        val p = pos(42, false, 100)
        assertNull(BleBeaconPlan.target(tag(p).copy(lastN = -1), null, now))
        assertNull(BleBeaconPlan.target(tag(p, queuedAgoS = 3700, kind = "BEEP"), null, now)) // a Beep lives 1 h in the mailbox
        assertNull(BleBeaconPlan.target(tag(p).copy(pending = null), null, now))
    }
}

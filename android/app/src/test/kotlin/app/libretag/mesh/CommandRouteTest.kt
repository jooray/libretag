package app.libretag.mesh

import app.libretag.CommandKind
import app.libretag.data.CheckPlan
import app.libretag.proto.Command
import app.libretag.proto.LibreTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Opening Bluetooth (BLE_CONSOLE) never floods from our radio; the other commands keep their routes. */
class CommandRouteTest {
    private val now = 1_800_000_000_000L
    private val window = now + 20 * 60_000L

    @Test fun ownNodeIsZeroHopForTheConsoleAndNeverFlood() {
        assertEquals(CommandRoute.Local.ZERO_HOP, CommandRoute.local(CommandKind.BLE_CONSOLE, zeroHopCapable = true))
        assertEquals(CommandRoute.Local.NONE, CommandRoute.local(CommandKind.BLE_CONSOLE, zeroHopCapable = false))
        for (k in CommandKind.entries) {
            val l = CommandRoute.local(k, zeroHopCapable = false)
            if (k == CommandKind.BLE_CONSOLE) assertTrue(l != CommandRoute.Local.FLOOD)
            else assertEquals(k.name, CommandRoute.Local.FLOOD, l)
        }
    }

    @Test fun otherCommandsKeepEveryWay() {
        for (k in CommandKind.entries.filter { it != CommandKind.BLE_CONSOLE }) {
            val p = CommandRoute.plan(k, beaconOk = true, zeroHopOk = true, nextWindowAt = window, nextScanAt = window)
            assertTrue(p.beacon && p.mailbox)
            assertEquals(CommandRoute.Local.FLOOD, p.local)
        }
    }

    @Test fun bluetoothAdvertFirstWhenTheNextWakeUpScans() {
        val p = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = false, nextWindowAt = window, nextScanAt = window)
        assertEquals(CommandRoute.Via.BLE, p.via)
        assertTrue(p.beacon)
        assertFalse(p.mailbox)
        assertEquals(CommandRoute.Local.NONE, p.local)
        assertEquals(window, p.at)
        // with a zero-hop node as well: both local, still no Nostr
        val q = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = true, nextWindowAt = window, nextScanAt = window)
        assertEquals(CommandRoute.Via.BLE, q.via)
        assertEquals(CommandRoute.Local.ZERO_HOP, q.local)
        assertFalse(q.mailbox)
    }

    @Test fun zeroHopWhenTheAdvertWouldWaitForALaterWakeUp() {
        val later = window + 2 * 3600_000L // listen-only wake-ups in between scan nothing
        val p = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = true, nextWindowAt = window, nextScanAt = later)
        assertEquals(CommandRoute.Via.ZERO_HOP, p.via)
        assertEquals(CommandRoute.Local.ZERO_HOP, p.local)
        assertTrue(p.beacon) // harmless, no mesh
        assertFalse(p.mailbox)
        assertEquals(window, p.at)
    }

    @Test fun nostrOnlyWhenNoLocalWayReachesTheNextWakeUp() {
        val later = window + 2 * 3600_000L
        val p = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = false, nextWindowAt = window, nextScanAt = later)
        assertEquals(CommandRoute.Via.NOSTR, p.via)
        assertTrue(p.mailbox)
        assertEquals(CommandRoute.Local.NONE, p.local) // our radio still never floods it
        assertTrue(p.beacon)
        val q = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = false, zeroHopOk = false, nextWindowAt = window, nextScanAt = window)
        assertEquals(CommandRoute.Via.NOSTR, q.via)
        assertFalse(q.beacon)
        assertEquals(CommandRoute.Local.NONE, q.local)
    }

    @Test fun everyWakeUpScansSoTheNextCheckTakesTheAdvert() {
        val t0 = now - 10 * 60_000L
        val plan = CheckPlan(100, t0, 60, listOf(3600L, 7200L, 10800L, 14400L))
        // the listen-only check 1 scans too
        assertEquals(t0 + 3_600_000L, CommandRoute.nextScanAt(plan, null, now))
        // check 1 is over: check 2, also listen-only
        assertEquals(t0 + 7_200_000L, CommandRoute.nextScanAt(plan, null, t0 + 3_700_000L))
        // after the plan: the next report
        assertEquals(now + 5000, CommandRoute.nextScanAt(CheckPlan(100, t0, 60, listOf(60L)), now + 5000, now))
        assertNull(CommandRoute.nextScanAt(null, null, now))
        // so the phone's advert wins over zero-hop and Nostr at the very next listening wake-up
        val next = t0 + 3_600_000L
        val p = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = true, nextWindowAt = next,
            nextScanAt = CommandRoute.nextScanAt(plan, null, now))
        assertEquals(CommandRoute.Via.BLE, p.via)
        assertFalse(p.mailbox)
        assertEquals(next, p.at)
    }

    @Test fun notNearMeansTheAdvertRunsButIsNotCountedOn() {
        // P10: a Bluetooth sighting in the last 15 min, quiet because of the owner, or ACKed through the own node
        val t0 = 1_800_000_000_000L
        assertTrue(CommandRoute.isNear(bleSightAt = t0 - 10 * 60_000L, lastQuietReason = null, ackedByNode = false, now = t0))
        assertFalse(CommandRoute.isNear(bleSightAt = t0 - 20 * 60_000L, lastQuietReason = null, ackedByNode = false, now = t0))
        assertTrue(CommandRoute.isNear(bleSightAt = null, lastQuietReason = "owner", ackedByNode = false, now = t0))
        assertTrue(CommandRoute.isNear(bleSightAt = null, lastQuietReason = null, ackedByNode = true, now = t0))
        assertFalse(CommandRoute.isNear(bleSightAt = null, lastQuietReason = "zone", ackedByNode = false, now = t0))
        assertFalse(CommandRoute.isNear(bleSightAt = null, lastQuietReason = null, ackedByNode = false, now = t0))
        val p = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = true, nextWindowAt = window,
            nextScanAt = window, near = false)
        assertEquals(CommandRoute.Via.ZERO_HOP, p.via)
        assertTrue(p.beacon)
        val q = CommandRoute.plan(CommandKind.BLE_CONSOLE, beaconOk = true, zeroHopOk = false, nextWindowAt = window,
            nextScanAt = window, near = false)
        assertEquals(CommandRoute.Via.NOSTR, q.via)
        assertTrue(q.mailbox && q.beacon)
        assertEquals(CommandRoute.Local.NONE, q.local)
    }

    @Test fun aLocalWayThatMissedItsWakeUpFallsBackToTheMailbox() {
        val ble = CommandRoute.Via.BLE.name
        // the report came long after the wake-up the advert was meant for, still no CMD_ACK
        assertTrue(CommandRoute.mailboxFallback(false, ble, window, window + 2 * 3600_000L))
        assertTrue(CommandRoute.mailboxFallback(false, CommandRoute.Via.ZERO_HOP.name, window, window + 3600_000L))
        // that wake-up has not passed yet (the same wake-up, inside the guard)
        assertFalse(CommandRoute.mailboxFallback(false, ble, window, window + 30_000L))
        assertFalse(CommandRoute.mailboxFallback(false, ble, window, window - 3600_000L))
        // already in the mailbox, or no time known, or the old mix
        assertFalse(CommandRoute.mailboxFallback(true, ble, window, window + 2 * 3600_000L))
        assertFalse(CommandRoute.mailboxFallback(false, ble, 0L, window + 2 * 3600_000L))
        assertFalse(CommandRoute.mailboxFallback(false, "", window, window + 2 * 3600_000L))
    }

    @Test fun bleConsoleCommandMatchesTheVectors() {
        val v = Json.parseToJsonElement(File("../../host/tests/vectors/lt2.json").readText()).jsonObject
        val c = Command.bleConsole(14)
        assertEquals("220e0600", LibreTag.bytesToHex(c.encode()).substring(0, 8))
        assertEquals(30, Command.bleConsole(1, 300).args[0].toInt() and 0xFF)
        val pts = (v["commands"]!!.jsonArray + v["ble_commands"]!!.jsonArray).map { it.jsonObject["pt"]!!.jsonPrimitive.content }
        assertTrue(LibreTag.bytesToHex(Command.bleConsole(14).encode()) in pts)
        assertTrue(LibreTag.bytesToHex(Command.bleConsole(15, 300).encode()) in pts)
        assertTrue(LibreTag.bytesToHex(Command.bleConsole(16).encode()) in pts)
        assertArrayEquals(Command.bleConsole(16).encode(), CommandKind.BLE_CONSOLE.build(16).encode())
        assertTrue(v["ble_console_cmd"]!!.jsonArray.isNotEmpty())
    }
}

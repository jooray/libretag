package app.libretag.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Against host/tests/vectors/lt2.json, the vectors the Python reference and the C core use. */
class Lt2Test {
    private val v: JsonObject = Json.parseToJsonElement(File("../../host/tests/vectors/lt2.json").readText()).jsonObject
    private fun h(k: String) = LibreTag.hexToBytes(v[k]!!.jsonPrimitive.content)!!
    private fun h(o: JsonObject, k: String) = LibreTag.hexToBytes(o[k]!!.jsonPrimitive.content)!!
    private val keys = Keys(h("k0"))

    @Test fun keys() {
        assertArrayEquals(h("id"), keys.id)
        assertArrayEquals(h("usb_key"), keys.usbKey)
        assertArrayEquals(h("dm_sk"), keys.dmSk)
        assertArrayEquals(h("view0"), keys.view(0))
        assertArrayEquals(h("view2"), keys.view(2))
        assertArrayEquals(h("cmd2"), keys.cmd(2))
        assertArrayEquals(h("usb_response"), LibreTag.usbAuth(keys.usbKey, h("usb_challenge")))
    }

    @Test fun mailboxKeysCommitAndHistory() {
        for (e in v["mailbox_keys"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val ce = keys.cmd(LibreTag.epoch(n))
            assertArrayEquals(h(o, "sk"), LibreTag.mailboxSk(ce, n))
            assertArrayEquals(h(o, "pk"), LibreTag.mailboxPk(ce, n))
        }
        for (e in v["reports"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            assertTrue(LibreTag.commitMatches(h(o, "pk"), h(o, "p")))
            assertFalse(LibreTag.commitMatches(LibreTag.mailboxPk(keys.cmd(LibreTag.epoch(n + 1)), n + 1), h(o, "p")))
            val moved = h(o, "p").also { it[5] = (it[5].toInt() xor 1).toByte() }
            assertNotNull(LibreTag.openReport(keys.view(LibreTag.epoch(n)), n, o["j"]!!.jsonPrimitive.int, moved))
        }
        for (e in v["history"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val j = o["j"]!!.jsonPrimitive.int
            assertArrayEquals(h(o, "p"), keys.sealReport(n, j, h(o, "pt")))
            assertNotNull(LibreTag.openReport(keys.view(0), n, j, h(o, "p")))
        }
    }

    @Test fun reports() {
        for (e in v["reports"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.int.toLong()
            val j = o["j"]!!.jsonPrimitive.int
            val ve = keys.view(LibreTag.epoch(n))
            assertArrayEquals(h(o, "tag"), LibreTag.reportTag(ve, n, j))
            assertArrayEquals(h(o, "p"), keys.sealReport(n, j, h(o, "pt")))
            val r = LibreTag.openReport(ve, n, j, h(o, "p"))
            assertNotNull(r)
            assertNotNull(LibreTag.openReport(ve, n, j, h(o, "p_stealth")))
            assertNull(LibreTag.openReport(ve, n + 1, j, h(o, "p")))
            val w = ReportWindow(keys, maxOf(-1L, n - 10))
            val hit = w.tryOpen(h(o, "p"))!!
            assertEquals(n, hit.first)
            assertEquals(j, hit.second)
            assertNull(w.tryOpen(h(o, "p"))) // seen
        }
        val r0 = LibreTag.openReport(keys.view(0), 5, 0, h(v["reports"]!!.jsonArray[0].jsonObject, "p"))!!
        assertTrue(r0.hasFix)
        assertEquals(48.700000, r0.lat, 2e-5)
        assertEquals(listOf(Neighbour("a5e1", 11.0), Neighbour("b7d2", 7.5)), r0.neighbours)
        val r1 = LibreTag.openReport(keys.view(2), 130, 1, h(v["reports"]!!.jsonArray[1].jsonObject, "p"))!!
        assertEquals(LastFix(1763100000, -33.8688, -151.2093).time, r1.lastFix!!.time)
        assertEquals(Mode.LOST, r1.mode)
        assertTrue(r1.nbDiscover && r1.lowBatt)
        assertEquals(5, r1.neighbours.size)
        assertFalse(r1.quiet)
        assertNull(r1.quietReason)
        assertNull(r1.nextInS)
        assertNull(r1.energyMah)
        assertNull(r1.ble)
        assertNull(r1.wifi)
    }

    private fun report(i: Int): Report = Report.decode(h(v["reports"]!!.jsonArray[i].jsonObject, "pt"))!!

    @Test fun reports06() {
        assertTrue(v["reports"]!!.jsonArray.size >= 6)
        val r3 = report(3)
        assertTrue(r3.quiet && !r3.quietOwner)
        assertEquals("zone", r3.quietReason)
        assertEquals(86400L, r3.nextInS)
        assertEquals(340, r3.energyMah)
        assertEquals(listOf(Neighbour("a5e1", 9.5)), r3.neighbours)
        assertNull(r3.ble)
        assertNull(r3.wifi)

        val r4 = report(4)
        assertTrue(r4.quiet && r4.quietOwner)
        assertEquals("owner", r4.quietReason)
        assertEquals(28800L, r4.nextInS)
        assertEquals(0, r4.energyMah)
        assertEquals(BleSummary(17, 5, -61), r4.ble)
        assertEquals(
            listOf(Neighbour("a5e1", 8.0), Neighbour("b7d2", 2.25), Neighbour("c3f4", -3.0), Neighbour("d4a6", -6.5)),
            r4.neighbours,
        )
        assertEquals(6, r4.nbTotal)
        assertNull(r4.wifi)

        val r5 = report(5)
        assertEquals(Mode.LOST, r5.mode)
        assertTrue(r5.cmdAck)
        assertEquals(4, r5.cmdSeq)
        assertFalse(r5.quiet)
        assertEquals(120L, r5.nextInS)
        assertEquals(2540, r5.energyMah)
        assertEquals(listOf(Neighbour("b7d2", -2.0)), r5.neighbours)
        assertNull(r5.ble)
        val w = r5.wifi!!
        assertEquals("0024a5112233", w.seedHex)
        assertEquals(-57, w.seedRssi) // 5-bit q: -95 + 2 q
        assertEquals(7, w.apCount)
        assertEquals("44aa21", w.tailHex)
        assertEquals(-71, w.tailRssi)
    }

    /** 0.7 reports (n 203..205): parked + CHECKS, owner + CHECKS with an ack, CHECKS while reporting. */
    @Test fun reports07() {
        val all = v["reports"]!!.jsonArray.map { it.jsonObject }
        fun byN(n: Int) = Report.decode(h(all.first { it["n"]!!.jsonPrimitive.int == n }, "pt"))!!
        val parked = byN(203)
        assertTrue(parked.quiet && parked.parked && parked.checks)
        assertEquals("parked", parked.quietReason)
        assertEquals(Mode.IDLE, parked.mode)
        assertEquals(6 * 3600L, parked.nextInS)
        val owner = byN(204)
        assertTrue(owner.quiet && owner.checks && owner.cmdAck)
        assertEquals("owner", owner.quietReason)
        assertEquals(8 * 3600L, owner.nextInS)
        val moving = byN(205)
        assertTrue(moving.checks && moving.hasFix)
        assertFalse(moving.quiet)
        assertNull(moving.quietReason)
        // 0.6 reports carry neither
        for (i in 0 until 6) assertFalse(report(i).checks || report(i).parked)
    }

    /** The keyed check schedule (checks-0.7.md "Schedule"). */
    @Test fun checks() {
        for (e in v["checks"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val base = o["base_s"]!!.jsonPrimitive.long
            val hb = o["heartbeat_s"]!!.jsonPrimitive.long
            val ce = keys.cmd(LibreTag.epoch(n))
            val jit = o["jitter"]!!.jsonArray.map { it.jsonPrimitive.long }
            jit.forEachIndexed { i, j -> assertEquals("n=$n i=${i + 1}", j, LibreTag.checkJitter(ce, n, i + 1, base)) }
            val offs = o["offsets"]!!.jsonArray.map { it.jsonPrimitive.long }
            assertEquals(offs, LibreTag.checkOffsets(ce, n, (base / 60).toInt(), Long.MAX_VALUE, max = offs.size))
            assertEquals(o["before_heartbeat"]!!.jsonArray.map { it.jsonPrimitive.long },
                LibreTag.checkOffsets(ce, n, (base / 60).toInt(), hb, max = 64))
        }
    }

    /** Commands at check k >= 1 (u16(k) in the derivation); k = 0 stays the 0.6 command. */
    @Test fun checkCommands() {
        for (e in v["check_commands"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val k = o["k"]!!.jsonPrimitive.int
            val ce = keys.cmd(LibreTag.epoch(n))
            assertArrayEquals(h(o, "tag"), LibreTag.commandTag(ce, n, k))
            assertArrayEquals(h(o, "tag_k0"), LibreTag.commandTag(ce, n))
            val cmd = Command.decode(h(o, "pt"))!!
            assertArrayEquals(h(o, "p"), LibreTag.sealCommand(ce, n, cmd, k))
            assertArrayEquals(h(o, "pt"), LibreTag.openCommand(ce, n, h(o, "p"), k)!!.encode())
            assertNull(LibreTag.openCommand(ce, n, h(o, "p")))
        }
        // the SET_IDLE vectors with check_s (n 204: 120 min + 30 s; n 64: CHECK_OFF)
        val c = v["check_commands"]!!.jsonArray.map { it.jsonObject }
        assertArrayEquals(h(c.first { it["n"]!!.jsonPrimitive.int == 204 }, "pt"), Command.setIdle(9, 120, 0, 30).encode())
        assertArrayEquals(h(c.first { it["n"]!!.jsonPrimitive.int == 64 }, "pt"), Command.setIdle(10, 0, 0, Command.CHECK_OFF).encode())
    }

    @Test fun reportEvery073() {
        // SET_IDLE with report_every at offset 8
        val cmd = Command.setIdle(11, 60, 0, 30, 3)
        assertArrayEquals(h("set_idle_report_every"), cmd.encode())
        assertEquals(3, Command.idleReportEvery(cmd))
        assertNull(Command.idleReportEvery(Command.setIdle(9, 120, 0, 30)))
        // the wake-ups between reports are the keyed check schedule; next_in is wake-up n
        for (e in v["report_every"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val base = o["base_s"]!!.jsonPrimitive.int
            val every = o["every"]!!.jsonPrimitive.int
            val offs = o["offsets"]!!.jsonArray.map { it.jsonPrimitive.long }
            val ce = keys.cmd(LibreTag.epoch(n))
            assertEquals(offs, LibreTag.checkOffsets(ce, n, base / 60, Long.MAX_VALUE, offs.size))
            assertEquals(o["next_in"]!!.jsonPrimitive.long, offs[every - 1])
            // the app's plan between reports: checks strictly before next_in
            assertEquals(offs.take(every - 1), LibreTag.checkOffsets(ce, n, base / 60, offs[every - 1]))
        }
    }

    @Test fun nextInAndEnergy() {
        for (e in v["next_in"]!!.jsonArray) {
            val a = e.jsonArray
            val sec = a[0].jsonPrimitive.long
            val code = a[1].jsonPrimitive.int
            assertEquals(code, LibreTag.nextInEncode(sec))
            assertEquals(a[2].jsonPrimitive.content.toLongOrNull(), LibreTag.nextInDecode(code))
        }
        assertEquals(0, LibreTag.nextInEncode(null))
        for (e in v["energy"]!!.jsonArray) {
            val a = e.jsonArray
            val code = a[1].jsonPrimitive.int
            assertEquals(code, LibreTag.energyEncode(a[0].jsonPrimitive.double))
            assertTrue(LibreTag.energyDecode(code)!! <= a[0].jsonPrimitive.double)
        }
        assertNull(LibreTag.energyDecode(0))
        assertEquals(0, LibreTag.energyEncode(null))
    }

    @Test fun ble() {
        val sv = v["ble_survey"]!!.jsonArray.map { it.jsonPrimitive.int }
        val devs = v["ble_devices"]!!.jsonArray.map { it.jsonObject }
        val sets = v["ble"]!!.jsonArray.map { it.jsonObject }
        for (set in sets) {
            val n = set["n"]!!.jsonPrimitive.long
            val ve = keys.view(LibreTag.epoch(n))
            val pkts = set["packets"]!!.jsonArray.map { it.jsonObject }
            val w = ReportWindow(keys, n)
            val all = ArrayList<BleDevice>()
            for ((k, o) in pkts.withIndex()) {
                val j = o["j"]!!.jsonPrimitive.int
                assertEquals(LibreTag.J_BLE + k, j)
                assertEquals(LibreTag.BLE_P_LEN, h(o, "p").size)
                assertEquals(LibreTag.BLE_P_LEN_STEALTH, h(o, "p_stealth").size)
                assertArrayEquals(h(o, "p"), LibreTag.sealExtra(ve, n, j, h(o, "pt")))
                assertArrayEquals(h(o, "p_stealth"), LibreTag.sealExtra(ve, n, j, h(o, "pt"), stealth = true))
                val b = LibreTag.openExtra(ve, n, j, h(o, "p")) as BlePacket
                assertTrue(LibreTag.openExtra(ve, n, j, h(o, "p_stealth")) is BlePacket)
                assertNull(LibreTag.openExtra(ve, n, j + 1, h(o, "p")))
                assertNull(LibreTag.openReport(ve, n, j, h(o, "p")))
                assertEquals(k, b.k)
                assertEquals(pkts.size, b.total)
                assertEquals(BleSurvey(sv[0], sv[1] != 0, sv[2], sv[3], sv[4], sv[5], sv[6]), b.survey)
                val hit = w.tryOpenExtra(h(o, "p"))!!
                assertEquals(n, hit.first)
                assertEquals(j, hit.second)
                assertTrue(hit.third is BlePacket)
                assertNull(w.tryOpen(h(o, "p")))
                all += b.devices
            }
            // records, in order, as the vector devices (possibly fewer when truncated)
            for ((i, d) in all.withIndex()) {
                val e = devs[i]
                assertEquals(e["addr"]!!.jsonPrimitive.content, d.addrHex)
                val info = e["info"]!!.jsonPrimitive.int
                assertEquals(info and 3, d.kind)
                assertEquals(info and BleDevice.BI_CONNECTABLE != 0, d.connectable)
                assertEquals(info and BleDevice.BI_SCAN_RSP != 0, d.scanRsp)
                assertEquals(info and BleDevice.BI_ANCHOR != 0, d.anchor)
                assertEquals(e["rssi"]!!.jsonPrimitive.int, d.rssi)
                assertEquals(e["heard"]!!.jsonPrimitive.int, d.heard)
                assertTrue(e["ad"]!!.jsonPrimitive.content.startsWith(LibreTag.bytesToHex(d.ad)))
            }
        }
        val full = sets[0]["packets"]!!.jsonArray.map { it.jsonObject }
            .flatMap { BlePacket.decode(h(it, "pt"))!!.devices }
        assertFalse(BlePacket.decode(h(sets[0]["packets"]!!.jsonArray[0].jsonObject, "pt"))!!.truncated)
        assertEquals(listOf("Printer 0001", "Bike Trainer 01", "Lock_00000002", null, null, null), full.map { it.name })
        assertEquals(0x0040, full[0].company)
        assertEquals(0x004c, full[2].company)
        assertTrue(full[1].anchor)
        assertEquals(BleDevice.ADDR_RPA, full[3].kind)
        assertEquals(BleDevice.ADDR_NRPA, full[5].kind)
        assertEquals(listOf(0x09, 0x03, 0x16), full[1].ads().map { it.first })
        val one = BlePacket.decode(h(sets[1]["packets"]!!.jsonArray[0].jsonObject, "pt"))!!
        assertEquals(1, sets[1]["packets"]!!.jsonArray.size)
        assertEquals(1, one.total)
        assertTrue(one.truncated)
        assertTrue(one.devices.size < 6)
    }

    @Test fun wifi() {
        for (e in v["wifi"]!!.jsonObject["cases"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val ve = keys.view(LibreTag.epoch(n))
            assertArrayEquals(h(o, "p"), LibreTag.sealExtra(ve, n, LibreTag.J_WIFI, h(o, "pt")))
            val pk = LibreTag.openExtra(ve, n, LibreTag.J_WIFI, h(o, "p")) as WifiPacket
            assertNull(LibreTag.openReport(ve, n, LibreTag.J_WIFI, h(o, "p")))
            assertEquals(8, pk.apCount) // the _nomap AP is left out
            assertEquals("44d9e7000002", pk.seedHex)
            assertEquals(-65, pk.seedRssi)
            assertEquals(listOf("000001", "000003", "000005", "000006", "000007", "000008"),
                pk.tails.map { LibreTag.bytesToHex(it.first) })
            assertEquals(listOf(-49, -71, -73, -83, -87, -89), pk.tails.map { it.second })
            val stealth = LibreTag.sealExtra(ve, n, LibreTag.J_WIFI, h(o, "pt"), stealth = true)
            assertTrue(LibreTag.openExtra(ve, n, LibreTag.J_WIFI, stealth) is WifiPacket)
            val hit = ReportWindow(keys, n - 3).tryOpenExtra(h(o, "p"))!!
            assertEquals(n, hit.first)
            assertEquals(LibreTag.J_WIFI, hit.second)
            assertTrue(hit.third is WifiPacket)
            assertNull(ReportWindow(keys, n + 20).tryOpenExtra(h(o, "p"))) // only near the newest report
        }
    }

    @Test fun rpa() {
        val o = v["rpa"]!!.jsonObject
        assertTrue(LibreTag.rpaMatches(h(o, "irk"), h(o, "match")))
        assertFalse(LibreTag.rpaMatches(h(o, "irk"), h(o, "other")))
        assertFalse(LibreTag.rpaMatches(h(o, "irk"), LibreTag.hexToBytes("b081940dfbaa")!!))
    }

    @Test fun commands() {
        for (e in v["commands"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.int.toLong()
            val ce = keys.cmd(LibreTag.epoch(n))
            val c = Command.decode(h(o, "pt"))!!
            assertArrayEquals(h(o, "p"), LibreTag.sealCommand(ce, n, c))
            assertEquals(c.op, LibreTag.openCommand(ce, n, h(o, "p"))!!.op)
            assertNull(LibreTag.openCommand(keys.view(LibreTag.epoch(n)), n, h(o, "p")))
        }
        val last = v["commands"]!!.jsonArray[3].jsonObject
        val bs = Command.bleScan(5, 10, true, 3)
        assertArrayEquals(h(last, "pt"), bs.encode())
        assertArrayEquals(h(last, "p"), LibreTag.sealCommand(keys.cmd(LibreTag.epoch(202)), 202, bs))
        assertEquals(Command.OP_BLE_SCAN, bs.op)
        assertArrayEquals(byteArrayOf(20, 0, 4), Command.bleScan(1, 99, false, 9).args)
    }

    /** 0.9.4: BOOT bit (byte 2 bit 2) and the reset information in byte 14 of a boot report without a fix */
    @Test fun bootReports() {
        for (e in v["boot_reports"]!!.jsonArray) {
            val o = e.jsonObject
            val n = o["n"]!!.jsonPrimitive.long
            val j = o["j"]!!.jsonPrimitive.int
            assertArrayEquals(h(o, "p"), keys.sealReport(n, j, h(o, "pt")))
            val r = LibreTag.openReport(keys.view(LibreTag.epoch(n)), n, j, h(o, "p"))!!
            assertEquals(o["boot"]!!.jsonPrimitive.boolean, r.boot)
            assertEquals(o["has_fix"]!!.jsonPrimitive.boolean, r.hasFix)
            assertEquals(Mode.entries[o["mode"]!!.jsonPrimitive.int], r.mode)
            assertEquals(o["boot_cause"]!!.jsonPrimitive.contentOrNull, r.bootCause)
            assertEquals(o["boot_resets"]!!.jsonPrimitive.intOrNull, r.bootResets)
            assertEquals(o["hacc_m"]!!.jsonPrimitive.intOrNull, r.haccM)
        }
        for (e in v["reports"]!!.jsonArray) {
            val r = Report.decode(h(e.jsonObject, "pt"))!!
            assertFalse(r.boot)
            assertNull(r.bootCause)
        }
    }
}

package app.libretag.usb

import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import app.libretag.proto.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class ConsoleParsersTest {
    private fun lines(s: String) = s.trimIndent().replace("\n", "\r\n").split('\n')

    // ------------------------------------------------------------------ version

    @Test fun version() {
        val v = ConsoleParsers.parseVersion(lines("""
            version
            LibreTag tracker 0.5.2 on ZephCore da3513aa6d08, board wio_tracker_l1
        """))!!
        assertEquals(FirmwareVersion("0.5.2", "da3513aa6d08", "wio_tracker_l1"), v)
        assertNull(ConsoleParsers.parseVersion(listOf("ERR unknown command")))
    }

    @Test fun versionOrder() {
        assertTrue(ConsoleParsers.isOlder("0.5.2", "0.5.3"))
        assertTrue(ConsoleParsers.isOlder("0.9.9", "0.10.0"))
        assertTrue(ConsoleParsers.isOlder("0.6.0-rc1", "0.6.0"))
        assertTrue(ConsoleParsers.isOlder("0.6.0-rc.2", "0.6.0-rc.10"))
        assertTrue(ConsoleParsers.isOlder("0.5", "0.5.1"))
        assertFalse(ConsoleParsers.isOlder("0.5.2", "0.5.2"))
        assertFalse(ConsoleParsers.isOlder("v1.0.0", "0.99.0"))
        assertEquals(0, ConsoleParsers.compareVersions("1.0", "1.0.0"))
        assertEquals(0, ConsoleParsers.compareVersions("1.0.0+build7", "1.0.0"))
    }

    // ------------------------------------------------------------------ history

    /** LT2 report plaintext (PROTOCOL.md 2) with a fix, no neighbours. */
    private fun reportPt(lat: Double, lon: Double, t: Long, batt: Int): ByteArray {
        val b = ByteBuffer.allocate(LibreTag.REPORT_PT_LEN).order(ByteOrder.LITTLE_ENDIAN)
        fun s24(v: Int) { b.put(v.toByte()); b.put((v shr 8).toByte()); b.put((v shr 16).toByte()) }
        b.put(LibreTag.TYPE_REPORT.toByte()).put(LibreTag.F_HAS_FIX.toByte()).put(Mode.IDLE.ordinal.toByte()).put(batt.toByte())
        b.putInt(t.toInt())
        s24((lat * (1 shl 23) / 90).roundToInt())
        s24((lon * (1 shl 23) / 180).roundToInt())
        b.put(12.toByte()).put(0.toByte())
        return b.array()
    }

    @Test fun historyRoundTripsThroughOpenReport() {
        val keys = Keys(ByteArray(32) { (0xA0 + it).toByte() })
        val recs = listOf(127L, 130L).map { n ->
            val p = keys.sealReport(n, HistoryRecord.HISTORY_J, reportPt(48.70 + n * 1e-4, 21.25, 1_800_000_000L + n, 80))
            "H $n ${HistoryRecord.HF_FIX or HistoryRecord.HF_RELAYED} ${LibreTag.bytesToHex(p).uppercase()}"
        }
        val out = ConsoleParsers.parseHistory(listOf("history") + recs + listOf("END", "H 999 1 00"))
        assertEquals(2, out.size)
        assertEquals(listOf(127L, 130L), out.map { it.n })
        assertTrue(out.all { it.fix && it.relayed && !it.dropped })
        for (h in out) {
            val r = LibreTag.openReport(keys.view(LibreTag.epoch(h.n)), h.n, HistoryRecord.HISTORY_J, h.p)
            assertNotNull(r)
            assertEquals(48.70 + h.n * 1e-4, r!!.lat, 1e-4)
            assertEquals(21.25, r.lon, 1e-4)
            assertEquals(80, r.batteryPct)
            // copy j = 0 does not open it: it was never on air
            assertNull(LibreTag.openReport(keys.view(LibreTag.epoch(h.n)), h.n, 0, h.p))
        }
    }

    // ------------------------------------------------------------------ log

    @Test fun log() {
        val out = ConsoleParsers.parseLog(lines("""
            log 3
            LOG 3 of 41 report(s): n uptime_s search_s batt mode repeaters flags
            L 38 86400 34 87 0 2 fix relayed
            L 39 90000 90 86 3 0 nofix not-relayed retx DROPPED cmd border discover low
            L 40 93600 12 255 1 5 fix not-relayed
            END
        """))
        assertEquals(3, out.size)
        assertEquals(LogRecord(38, 86400, 34, 87, 0, 2, fix = true, relayed = true, retransmitted = false,
            dropped = false, command = false, border = false, discover = false, lowBattery = false), out[0])
        with(out[1]) {
            assertFalse(fix); assertFalse(relayed); assertTrue(retransmitted); assertTrue(dropped)
            assertTrue(command); assertTrue(border); assertTrue(discover); assertTrue(lowBattery)
            assertEquals(Mode.LOST, modeEnum)
        }
        assertNull(out[2].batteryPct)
        assertEquals(Mode.TRACK, out[2].modeEnum)
    }

    // ------------------------------------------------------------------ blescan

    @Test fun bleScan() {
        val s = ConsoleParsers.parseBleScan(lines("""
            OK active BLE scan 20 s
            blescan: 57 advert(s) from 4 device(s)
             02:00:00:00:00:01 public rssi -48 x12 co FFFF ""
             C2:00:00:00:00:02 static rssi -70 x3 co 0059 "Oura ring"
             4A:00:00:00:00:03 rpa    rssi -81 x20 co 004C ""
             3A:00:00:00:00:04 nrpa   rssi -90 x1 co 0006 "say "hi" now"
        """))!!
        assertEquals(57, s.adverts)
        assertEquals(4, s.deviceCount)
        assertEquals(4, s.devices.size)
        assertEquals(BleDevice("02:00:00:00:00:01", BleAddressKind.PUBLIC, -48, 12, null, null), s.devices[0])
        assertEquals(BleDevice("C2:00:00:00:00:02", BleAddressKind.STATIC, -70, 3, 0x0059, "Oura ring"), s.devices[1])
        assertEquals(BleAddressKind.RPA, s.devices[2].kind)
        assertEquals(0x004C, s.devices[2].companyId)
        assertEquals("say \"hi\" now", s.devices[3].name)
        assertEquals(listOf(true, true, false, false), s.devices.map { it.stable })
        assertNull(ConsoleParsers.parseBleScan(listOf("ERR bluetooth -5")))
    }

    @Test fun bleRowForwardCompatible() {
        assertEquals("a \"b\" c", ConsoleParsers.parseBleRow(""" 02:00:00:00:00:05 static rssi -60 x2 co 0075 "a \"b\" c"""")!!.name)
        assertEquals("café", ConsoleParsers.parseBleRow(""" 02:00:00:00:00:05 static rssi -60 x2 co 0075 "caf\xe9"""")!!.name)
        assertEquals(BleAddressKind.UNKNOWN, ConsoleParsers.parseBleRow(" 02:00:00:00:00:06 weird rssi -60 x2 co FFFF")!!.kind)
        assertNull(ConsoleParsers.parseBleRow("blescan: 1 advert(s) from 1 device(s)"))
    }

    /**
     * A realistic 0.7.4 `blescan 30` capture, written from main_tracker.cpp's printf formats
     * (ble_print_dev, ble_scan_print): echo, OK, log lines in between, the header, D lines with
     * names that hold spaces, quotes and "fp=", maker data, an OWNER row, empty table slots printed
     * as zero rows (the h[] overflow), a repeated address, END. Synthetic addresses.
     */
    @Test fun bleScan074Fixture() {
        val s = ConsoleParsers.parseBleScan(listOf(
            "blescan 30",
            "OK active BLE scan 30 s\r",
            "[00:12:31.004,000] <inf> tracker: BLE: scan started",
            "blescan: 212 advert(s) from 9 device(s), 0 dropped\r",
            "D 00:00:00:00:00:00 public rssi=0/0/0 n=0 pdu=00 conn=0 co=0000 ml=0 m= mchg=0 fp=0000",
            "D 02:00:00:00:00:10 public rssi=-60/-55/-48 n=31 rsp=12 pdu=01 conn=1 fl=06 ap=00c1 co=0087 ml=4 m=01020304 mchg=0 nameC=\"Forerunner 255\" fp=1C2D",
            "D C2:00:00:00:00:11 static rssi=-71/-66/-62 n=9 rsp=4 pdu=01 conn=1 fl=06 co=0B1C ml=6 m=0b1c01020304 mchg=0 nameC=\"Ring \"7\" fp=x\" OWNER fp=1A2B",
            "<wrn> usb: ring full, waiting",
            "D 4A:00:00:00:00:12 rpa rssi=-90/-86/-85 n=14 pdu=00 conn=1 fl=1a tx=12 co=004C ml=25 m=12190000000000000000000000000000000000000000000000 mchg=2 fp=4C12",
            "D C2:00:00:00:00:13 static rssi=-80/-74/-70 n=5 pdu=00 conn=0 u16=FDF0 sd=FDF0:8:0102030405060708 nameS=\"Hear\" fp=FFEE",
            "D 02:00:00:00:00:14 public rssi=-77/-77/-77 n=1 pdu=00 conn=0 fp=0001",
            "D 00:00:00:00:00:00 public rssi=0/0/0 n=0 pdu=00 conn=0 co=0000 ml=0 m= mchg=0 fp=0000",
            "D C2:00:00:00:00:15 static rssi=-58/-52/-45 n=40 pdu=01 conn=1 co=0040 ml=3 m=010203 mchg=0 fp=0040",
            "D 02:00:00:00:00:14 public rssi=-70/-69/-68 n=3 pdu=00 conn=0 nameC=\"Desk lamp\" fp=0001",
            "D garbage line",
            "END\r",
            "[00:12:61.010,000] <inf> tracker: BLE: scan stopped",
        ))!!
        assertEquals(212, s.adverts)
        assertEquals(9, s.deviceCount)
        val by = s.devices.associateBy { it.address }
        // no zero rows, one row per address
        assertEquals(listOf("02:00:00:00:00:10", "C2:00:00:00:00:11", "4A:00:00:00:00:12", "C2:00:00:00:00:13",
            "02:00:00:00:00:14", "C2:00:00:00:00:15"), s.devices.map { it.address })
        assertTrue(s.devices.none { it.rssi == 0 || it.address.startsWith("00:00:00") })
        val garmin = by.getValue("02:00:00:00:00:10")
        assertEquals("Forerunner 255", garmin.name); assertEquals(0x0087, garmin.companyId); assertEquals(0x00C1, garmin.appearance)
        assertEquals(-48, garmin.rssi); assertEquals(-60, garmin.rssiMin); assertEquals(-55, garmin.rssiAvg); assertEquals(31, garmin.heard)
        val ring = by.getValue("C2:00:00:00:00:11")
        assertEquals("Ring \"7\" fp=x", ring.name); assertTrue(ring.owner); assertEquals("1A2B", ring.fp)
        val apple = by.getValue("4A:00:00:00:00:12")
        assertEquals(0x004C, apple.companyId); assertNull(apple.name); assertFalse(apple.stable); assertEquals(BleAddressKind.RPA, apple.kind)
        val hear = by.getValue("C2:00:00:00:00:13")
        assertEquals("Hear", hear.name); assertEquals(listOf(0xFDF0), hear.uuids16); assertEquals(0xFDF0 to "0102030405060708", hear.serviceData)
        // the repeat: strongest signal, and the name the other copy had
        val lamp = by.getValue("02:00:00:00:00:14")
        assertEquals(-68, lamp.rssi); assertEquals("Desk lamp", lamp.name); assertEquals(3, lamp.heard)
        // named first, then strongest
        assertEquals(listOf("02:00:00:00:00:10", "C2:00:00:00:00:11", "02:00:00:00:00:14", "C2:00:00:00:00:13",
            "C2:00:00:00:00:15", "4A:00:00:00:00:12"), ConsoleParsers.forPicking(s.devices).map { it.address })
    }

    @Test fun bleDTolerant() {
        // fields in another order, single rssi, no n, no fp
        val d = ConsoleParsers.parseBleD("D 02:00:00:00:00:20 static co=0075 rssi=-63 nameC=\"Galaxy Buds\"")!!
        assertEquals(-63, d.rssi); assertEquals(0x0075, d.companyId); assertEquals("Galaxy Buds", d.name); assertEquals(1, d.heard)
        assertNull(d.rssiMin); assertNull(d.fp)
        assertNull(ConsoleParsers.parseBleD("D 00:00:00:00:00:00 public rssi=-50/-50/-50 n=2 fp=0000"))
        assertNull(ConsoleParsers.parseBleD("D 02:00:00:00:00:21 public rssi=0/0/0 n=4 fp=0000"))
        assertNull(ConsoleParsers.parseBleD("D 02:00:00:00:00:22 public rssi=-50/-50/-50 n=0 fp=0000"))
        assertNull(ConsoleParsers.parseBleD("D 02:00:00:00:00:23 public n=3 fp=0000"))
        assertNull(ConsoleParsers.parseBleD("blescan: 1 advert(s) from 1 device(s), 0 dropped"))
        assertNull(ConsoleParsers.parseBleRow(" 00:00:00:00:00:00 public rssi 0 x0 co 0000"))
        assertEquals("blescan 30", ConsoleCommands.bleScan(30))
    }

    // ------------------------------------------------------------------ zones

    @Test fun zones() {
        val z = ConsoleParsers.parseZones(lines("""
            zone list
            zones: 3 of 8
            zone 0 48.70120 21.25410 150
            1: 48.69000,21.26000 r=300 quiet
            zone 2 48.71 21.24 80m zerohop
            OK
        """))
        assertEquals(listOf(
            Zone(0, 48.70120, 21.25410, 150),
            Zone(1, 48.69, 21.26, 300, listOf("quiet")),
            Zone(2, 48.71, 21.24, 80, listOf("zerohop")),
        ), z)
        assertEquals(listOf(Zone(0, 48.7, 21.25, 100)), ConsoleParsers.parseZones(listOf("48.70000 21.25000 100")))
        assertEquals(emptyList<Zone>(), ConsoleParsers.parseZones(listOf("zone 0 148.7 21.25 100", "no zones")))
    }

    @Test fun commands() {
        assertEquals("zone add 48.70120 21.25410 150", ConsoleCommands.zoneAdd(48.7012, 21.2541, 150))
        assertEquals("zone add -48.70000 -21.25000 5", ConsoleCommands.zoneAdd(-48.7, -21.25, 5))
        assertEquals("history 120", ConsoleCommands.history(120))
        assertEquals("blescan 30 passive", ConsoleCommands.bleScan(30, passive = true))
        assertEquals("anchor add addr 02:00:00:AA:BB:01", ConsoleCommands.anchorAddAddress("02:00:00:aa:bb:01"))
        assertEquals("anchor add name Oura", ConsoleCommands.anchorAddName("Oura"))
        assertEquals("anchor add name Oura ring", ConsoleCommands.anchorAddName(" Oura   ring "))
        val bad = runCatching { ConsoleCommands.zoneAdd(91.0, 0.0, 10) }
        assertTrue(bad.isFailure)
        assertTrue(runCatching { ConsoleCommands.anchorAddName("say \"hi\"") }.isFailure)
        // the round trip a UI would do
        val back = ConsoleParsers.parseZones(listOf(ConsoleCommands.zoneAdd(48.7012, 21.2541, 150).removePrefix("zone add ")))
        assertEquals(Zone(0, 48.7012, 21.2541, 150), back.single())
    }

    // ------------------------------------------------------------------ anchors, blepair

    @Test fun log06() {
        val r = ConsoleParsers.parseLog(listOf(
            "L 12 340 2 86400 14 87 0 3 fix relayed quiet zone ",
            "L 13 341 3 90000 9 86 0 0 fix silent owner ",
            "END",
        ))
        assertEquals(2, r.size)
        assertEquals(12L, r[0].seq); assertEquals(340L, r[0].n); assertEquals(2, r[0].j)
        assertEquals(86400L, r[0].uptimeS); assertEquals(14, r[0].searchS); assertEquals(87, r[0].batteryPct); assertEquals(3, r[0].nbTotal)
        assertTrue(r[0].fix && r[0].relayed && r[0].quiet && r[0].zone)
        assertEquals(3, r[1].j); assertTrue(r[1].silent && r[1].owner); assertFalse(r[1].relayed)
    }

    @Test fun blescan06() {
        // synthetic addresses (02:00:00:..), formats from main_tracker.cpp ble_print_dev
        val s = ConsoleParsers.parseBleScan(listOf(
            "blescan 30 passive",
            "OK passive BLE scan 30 s",
            "blescan: 41 advert(s) from 3 device(s), 0 dropped",
            "D 02:00:00:00:00:A1 static rssi=-71/-64/-58 n=12 pdu=00 conn=1 fl=06 co=0B1C ml=6 m=0b1c010203 mchg=0 nameC=\"Ring \"7\"\" OWNER fp=1A2B",
            "D 4A:00:00:00:00:B2 rpa rssi=-90/-84/-80 n=5 rsp=2 pdu=03 conn=0 co=004C ml=10 m=4c001005 mchg=3 fp=00C3",
            "D 02:00:00:00:00:C3 public rssi=-60/-55/-50 n=20 pdu=00 conn=1 u16=FDF0,180F sd=FDF0:8:0102030405060708 nameS=\"Hear\" fp=FFEE",
            "END 3",
        ))!!
        assertEquals(41, s.adverts)
        assertEquals(3, s.devices.size)
        val a = s.devices[0]
        assertEquals("02:00:00:00:00:A1", a.address)
        assertEquals(BleAddressKind.STATIC, a.kind)
        assertEquals(-58, a.rssi); assertEquals(-71, a.rssiMin); assertEquals(-64, a.rssiAvg); assertEquals(12, a.heard)
        assertEquals(0x0B1C, a.companyId); assertEquals("Ring \"7\"", a.name)
        assertTrue(a.owner); assertEquals("1A2B", a.fp); assertEquals(true, a.connectable); assertTrue(a.stable)
        val b = s.devices[1]
        assertEquals(BleAddressKind.RPA, b.kind); assertFalse(b.stable); assertNull(b.name); assertFalse(b.owner)
        assertEquals(0x004C, b.companyId); assertEquals(false, b.connectable)
        val c = s.devices[2]
        assertEquals(listOf(0xFDF0, 0x180F), c.uuids16)
        assertEquals(0xFDF0 to "0102030405060708", c.serviceData)
        assertEquals("Hear", c.name); assertNull(c.companyId)
    }

    @Test fun endWithCount() {
        assertEquals(1, ConsoleParsers.parseHistory(listOf("H 1 5 2 1 0a0b", "END 1", "H 2 6 2 1 0a0b")).size)
        assertEquals(1, ConsoleParsers.parseZones(listOf("ZONE 0 48.7000000 21.2500000 150", "END 1")).size)
        assertEquals(1, ConsoleParsers.parseAnchors(listOf("ANCHOR 0 addr 02:00:00:00:00:01", "END 1 gps=on scan=passive")).size)
    }

    @Test fun history06() {
        val r = ConsoleParsers.parseHistory(listOf("history 0", "H 7 120 3 257 00ff", "H 8 121 2 1 0a0b", "END 2"))
        assertEquals(2, r.size)
        assertEquals(120L, r[0].n); assertEquals(3, r[0].j); assertEquals(7L, r[0].seq); assertEquals(257, r[0].flags)
        assertEquals(2, r[1].j)
    }

    @Test fun firmwareFormats() {
        // firmware 0.6: zone list / anchor list
        assertEquals(listOf(Zone(0, 48.7012, 21.2541, 150), Zone(1, 48.69, 21.26, 300)),
            ConsoleParsers.parseZones(listOf("ZONE 0 48.7012000 21.2541000 150", "ZONE 1 48.6900000 21.2600000 300", "END 2")))
        val a = ConsoleParsers.parseAnchors(listOf("ANCHOR 0 addr 02:00:00:00:00:0A", "ANCHOR 1 name Oura ring",
            "ANCHOR 2 irk 00112233445566778899aabbccddeeff 02:00:00:00:00:0B", "END 3 gps=off scan=passive"))
        assertEquals(AnchorEntry(0, "02:00:00:00:00:0A", null, null, "addr"), a[0])
        assertEquals(AnchorEntry(1, null, "Oura ring", null, "name"), a[1])
        assertEquals("00112233445566778899aabbccddeeff", a[2].irkHex)
        assertEquals("02:00:00:00:00:0B", a[2].address)
    }

    @Test fun anchors() {
        val a = ConsoleParsers.parseAnchors(lines("""
            anchor list
            anchor 0 02:00:00:00:00:0a
            1: name:"Oura ring"
            2 a1b2c3d4e5f6
            OK
        """))
        assertEquals(listOf(
            AnchorEntry(0, "02:00:00:00:00:0A", null, null),
            AnchorEntry(1, null, "Oura ring", null),
            AnchorEntry(2, null, null, "a1b2c3d4e5f6"),
        ), a)
    }

    @Test fun irks() {
        val k = ConsoleParsers.parseIrks(lines("""
            blepair
            OK pairing 60 s
            IRK 00112233445566778899AABBCCDDEEFF
            IRK 02:00:00:00:00:0B ffeeddccbbaa99887766554433221100
            IRK short
        """))
        assertEquals(listOf(
            IrkEntry("00112233445566778899aabbccddeeff", null),
            IrkEntry("ffeeddccbbaa99887766554433221100", "02:00:00:00:00:0B"),
        ), k)
        assertTrue(ConsoleParsers.isOk(listOf("OK pairing 60 s")))
        assertFalse(ConsoleParsers.isOk(listOf("ERR locked")))
    }
}

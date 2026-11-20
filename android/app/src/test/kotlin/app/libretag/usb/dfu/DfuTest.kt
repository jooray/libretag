package app.libretag.usb.dfu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Against host/tools/dfu.py (host/tests/vectors/dfu.json), which is byte-identical to adafruit-nrfutil. */
class DfuTest {
    private val v: JsonObject = Json.parseToJsonElement(File("../../host/tests/vectors/dfu.json").readText()).jsonObject
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val image get() = hex(v.s("image"))
    private val dat get() = hex(v.s("init_packet"))

    @Test fun crc16() {
        for (c in v["crc16"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["crc"]!!.jsonPrimitive.int, Crc16.of(hex(o.s("data"))))
        }
    }

    @Test fun headers() {
        for (h in v["headers"]!!.jsonArray) {
            val o = h.jsonObject
            assertArrayEquals(hex(o.s("header")), Hci.header(o["seq"]!!.jsonPrimitive.int, o["len"]!!.jsonPrimitive.int))
        }
    }

    @Test fun framesMatchTheReference() {
        val payloads = listOf(Hci.startPayload(image.size), Hci.initPayload(dat)) + Hci.dataPayloads(image) + listOf(Hci.stopPayload())
        val frames = v["frames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(frames.size, payloads.size)
        frames.zip(payloads).forEach { (f, p) ->
            assertArrayEquals(f.s("name"), hex(f.s("payload")), p)
            assertArrayEquals(f.s("name"), hex(f.s("frame")), Hci.frame(f["seq"]!!.jsonPrimitive.int, p))
        }
    }

    @Test fun acksDecodeEvenByteByByte() {
        val r = SlipReader()
        for (a in v["acks"]!!.jsonArray) {
            val o = a.jsonObject
            val bodies = hex(o.s("frame")).flatMap { r.feed(byteArrayOf(it)) }
            assertEquals(1, bodies.size)
            assertEquals(o["expected"]!!.jsonPrimitive.int, Hci.ackNumber(bodies[0]))
        }
        assertNull(Hci.ackNumber(byteArrayOf(0x10, 0, 0, 0))) // bad header checksum
    }

    @Test fun slipRoundTrip() {
        val d = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 1, 0xC0.toByte())
        assertArrayEquals(d, Slip.unescape(Slip.escape(d)))
        assertNull(Slip.unescape(byteArrayOf(0xDB.toByte(), 0x01)))
    }

    @Test fun initPacket() {
        val ip = InitPacket.parse(dat)!!
        assertEquals(0x52, ip.deviceType)
        assertEquals(listOf(0xFFFE), ip.softDevices)
        assertEquals(Crc16.of(image), ip.crc16)
    }

    // --- package

    @Test fun parsesTheHostPackage() {
        val p = DfuPackage.parse(Base64.getDecoder().decode(v.s("package")))
        assertEquals(v.s("package_version"), p.version)
        assertArrayEquals(image, p.image)
        assertArrayEquals(dat, p.initPacket)
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> files.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    private fun pkg(img: ByteArray = image, d: ByteArray = InitPacketFor(img), extra: String = "", board: String = "wio_tracker_l1") = zip(mapOf(
        "manifest.json" to """{"manifest":{"application":{"bin_file":"application.bin","dat_file":"application.dat"}$extra,"dfu_version":0.5}}""".toByteArray(),
        "application.bin" to img, "application.dat" to d,
        "libretag.json" to """{"board":"$board","version":"1.0.0"}""".toByteArray(),
    ))

    @Suppress("TestFunctionName")
    private fun InitPacketFor(img: ByteArray): ByteArray {
        val crc = Crc16.of(img)
        return byteArrayOf(0x52, 0, -1, -1, -1, -1, -1, -1, 1, 0, 0xFE.toByte(), 0xFF.toByte(), (crc and 0xFF).toByte(), (crc ushr 8).toByte())
    }

    private fun refused(z: ByteArray, why: String) {
        try { DfuPackage.parse(z); fail("accepted: $why") } catch (e: DfuPackageException) { /* expected */ }
    }

    @Test fun packageChecks() {
        assertEquals("1.0.0", DfuPackage.parse(pkg()).version)
        assertArrayEquals(InitPacketFor(image), dat)
        val badCrc = InitPacketFor(image).also { it[12] = (it[12] + 1).toByte() }
        refused(pkg(d = badCrc), "CRC mismatch")
        refused(pkg(extra = ""","softdevice":{"bin_file":"sd.bin","dat_file":"sd.dat"}"""), "SoftDevice in the package")
        refused(pkg(board = "t1000_e"), "other board")
        val otherType = InitPacketFor(image).also { it[0] = 0x51 }
        refused(pkg(d = otherType), "device type")
        val big = image.copyOf(FlashLayout.APP_MAX + 4)
        refused(pkg(img = big), "image reaching the key pages")
        val wrongAddr = image.copyOf().also { it[4] = 1; it[5] = 0x10; it[6] = 0; it[7] = 0 }
        refused(pkg(img = wrongAddr), "reset vector for another load address")
    }

    @Test fun keyPagesAreOutsideTheApplication() {
        // storage_partition (keys) at 0xD0000, LittleFS at 0xD4000: the largest accepted image ends before
        assertEquals(0xD0000, FlashLayout.APP_START + FlashLayout.APP_MAX)
        assertTrue(DfuPackage.checkImage(image.copyOf(FlashLayout.APP_MAX)).none { "key pages" in it })
        assertTrue(DfuPackage.checkImage(image.copyOf(FlashLayout.APP_MAX + 4)).any { "key pages" in it })
    }

    @Test fun bootloaderIds() {
        assertTrue(DfuDevices.isBootloader(0x2886, 0x1667, null))
        assertTrue(DfuDevices.isBootloader(0x2886, 0x9999, "TRACKER L1"))
        assertTrue(!DfuDevices.isBootloader(0x2fe3, 0x0007, "LibreTag Tracker"))
        assertTrue(!DfuDevices.isBootloader(0x2886, 0x9999, "Something else"))
    }

    // --- transfer against a simulated bootloader (hci_transport.c receive side)

    private class Clock : DfuClock {
        var t = 0L
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private class FakeBootloader(val clock: Clock, val dropEvery: Int = 0, val corruptAt: Int = -1, val failWriteAt: Int = -1, val silent: Boolean = false) : DfuLink {
        var expected = 1
        val packets = mutableListOf<ByteArray>()
        private val reader = SlipReader()
        private val out = ArrayDeque<Byte>()
        var frames = 0
        var writes = 0

        override fun write(bytes: ByteArray) {
            if (writes++ == failWriteAt) throw IOException("USB write failed")
            val b = bytes.copyOf()
            for (body in reader.feed(b)) {
                frames++
                if (silent) continue
                if (dropEvery > 0 && frames % dropEvery == 0) continue
                val bd = if (frames == corruptAt) body.copyOf().also { it[6] = (it[6] + 1).toByte() } else body
                if (Hci.ackNumber(bd) == null) continue
                val crc = Crc16.of(bd, 0, bd.size - 2)
                if (crc != ((bd[bd.size - 2].toInt() and 0xFF) or ((bd[bd.size - 1].toInt() and 0xFF) shl 8))) continue
                if ((bd[0].toInt() and 7) == expected) {
                    expected = (expected + 1) and 7
                    packets += bd.copyOfRange(4, bd.size - 2)
                }
                val h0 = expected shl 3
                Slip.escape(byteArrayOf(h0.toByte(), 0, 0, ((-h0) and 0xFF).toByte())).let {
                    out.add(0xC0.toByte()); it.forEach { x -> out.add(x) }; out.add(0xC0.toByte())
                }
            }
        }

        override fun read(timeoutMs: Int): ByteArray {
            if (out.isEmpty()) { clock.t += timeoutMs; return ByteArray(0) }
            val n = minOf(out.size, 64)
            return ByteArray(n) { out.removeFirst() }
        }

        fun type(p: ByteArray) = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
    }

    private fun check(bl: FakeBootloader) {
        assertEquals(Hci.START_PACKET, bl.type(bl.packets.first()))
        assertEquals(Hci.INIT_PACKET, bl.type(bl.packets[1]))
        assertEquals(Hci.STOP_DATA_PACKET, bl.type(bl.packets.last()))
        assertArrayEquals(Hci.startPayload(image.size), bl.packets[0])
        assertArrayEquals(dat, bl.packets[1].copyOfRange(4, bl.packets[1].size - 2))
        val got = ByteArrayOutputStream().apply { bl.packets.subList(2, bl.packets.size - 1).forEach { write(it, 4, it.size - 4) } }.toByteArray()
        assertArrayEquals(image, got)
    }

    @Test fun cleanTransfer() {
        val c = Clock()
        val bl = FakeBootloader(c)
        val phases = mutableListOf<DfuPhase>()
        var last = 0
        val d = SerialDfu(bl, c)
        d.run(image, dat, { phases += it }, { s, _ -> last = s })
        check(bl)
        assertEquals(0, d.resends)
        assertEquals(image.size, last)
        assertEquals(listOf(DfuPhase.START, DfuPhase.ERASING, DfuPhase.INIT, DfuPhase.DATA, DfuPhase.FINISHING, DfuPhase.DONE), phases)
        // it waited for the erase before the init packet
        assertTrue(c.t >= SerialDfu.eraseWaitMs(image.size))
        assertTrue(SerialDfu.eraseWaitMs(image.size) >= v["erase_wait_ms"]!!.jsonPrimitive.int)
    }

    @Test fun lostAndCorruptFramesAreSentAgain() {
        val c = Clock()
        val bl = FakeBootloader(c, dropEvery = 3, corruptAt = 2, failWriteAt = 4)
        val d = SerialDfu(bl, c)
        d.run(image, dat)
        check(bl)
        assertTrue(d.resends > 0)
    }

    @Test fun silentBootloaderFailsAtStart() {
        val c = Clock()
        try {
            SerialDfu(FakeBootloader(c, silent = true), c).run(image, dat)
            fail("no error")
        } catch (e: DfuTransferException) {
            assertEquals(DfuPhase.START, e.phase)
        }
    }
}

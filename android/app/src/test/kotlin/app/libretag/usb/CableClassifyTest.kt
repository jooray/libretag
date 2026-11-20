package app.libretag.usb

import app.libretag.data.Cable
import app.libretag.data.Tracker
import app.libretag.usb.CableClassify.Device
import app.libretag.usb.CableClassify.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CableClassifyTest {
    private val now = 1_800_000_000_000L

    @Test fun usbIds() {
        assertEquals(Raw.BOOTLOADER, CableClassify.classify(Device(0x2886, 0x1667)))
        assertEquals(Raw.BOOTLOADER, CableClassify.classify(Device(0x2886, 0x9999, "TRACKER L1")))
        assertEquals(Raw.BOOTLOADER, CableClassify.classify(Device(0x239A, 0x0029)))
        assertEquals(Raw.TAG_SERIAL, CableClassify.classify(Device(0x2fe3, 0x0007)))
        assertEquals(Raw.CP2102, CableClassify.classify(Device(0x10c4, 0xea60)))
        assertEquals(Raw.OTHER, CableClassify.classify(Device(0x1234, 0x5678)))
        assertEquals(Raw.BOOTLOADER, CableClassify.classifyAll(listOf(Device(0x1234, 1), Device(0x239A, 2))))
        assertEquals(Raw.NONE, CableClassify.classifyAll(emptyList()))
    }

    private val wio = Tracker(id = "w", name = "Wio", keyHex = "00", board = "wio_tracker_l1")
    private val wio2 = Tracker(id = "w2", name = "Wio 2", keyHex = "00")
    private val esp = Tracker(id = "e", name = "Heltec", keyHex = "00", wifiTag = true)

    @Test fun attributionOrder() {
        // the update started here wins
        assertEquals("w2", CableClassify.attribute(false, listOf(wio, wio2.copy(dfuStartedAt = now - 3600_000L), esp), setOf("w"), now)?.id)
        // else the only overdue nRF tag
        assertEquals("w", CableClassify.attribute(false, listOf(wio, wio2, esp), setOf("w", "e"), now)?.id)
        // else the only nRF tag
        assertEquals("w", CableClassify.attribute(false, listOf(wio, esp), emptySet(), now)?.id)
        // two and none overdue: nobody
        assertNull(CableClassify.attribute(false, listOf(wio, wio2), emptySet(), now))
        // an update a day and more ago does not count
        assertNull(CableClassify.attribute(false, listOf(wio.copy(dfuStartedAt = now - 30 * 3600_000L), wio2), emptySet(), now))
        // an ESP32 goes to the ESP32 tag
        assertEquals("e", CableClassify.attribute(true, listOf(wio, esp), emptySet(), now)?.id)
    }

    @Test fun noNrfTagOwned() {
        assertNull(CableClassify.attribute(false, listOf(esp), setOf("e"), now))
        assertNull(CableClassify.attribute(false, listOf(wio.copy(sharedBy = "x")), emptySet(), now))
    }

    @Test fun cableFor() {
        assertEquals(Cable.TAG, CableClassify.cableFor(wio, Raw.TAG_SERIAL, false, "w", null))
        assertEquals(Cable.BOOTLOADER, CableClassify.cableFor(wio, Raw.BOOTLOADER, false, null, "w"))
        assertEquals(Cable.NONE, CableClassify.cableFor(wio2, Raw.BOOTLOADER, false, null, "w"))
        assertEquals(Cable.ESP_DOWNLOAD, CableClassify.cableFor(esp, Raw.CP2102, true, null, "e"))
        assertEquals(Cable.NONE, CableClassify.cableFor(esp, Raw.CP2102, false, null, "e"))
    }
}

class EspSyncTest {
    @Test fun frameBytes() {
        val f = EspSync.FRAME
        assertEquals(46, f.size)
        assertEquals("c000082400000000000707122" + "0" + "55".repeat(32) + "c0",
            f.joinToString("") { "%02x".format(it) })
    }

    @Test fun answer() {
        val rom = byteArrayOf(0x00, 0xC0.toByte(), 0x01, 0x08, 0x04, 0x00, 0x12, 0x20, 0x55, 0x55, 0x00, 0x00, 0xC0.toByte())
        assertTrue(EspSync.isRomAnswer(rom))
        assertFalse(EspSync.isRomAnswer("ERR unknown command\r\n".toByteArray()))
        assertFalse(EspSync.isRomAnswer(byteArrayOf(0xC0.toByte(), 0x01)))
    }
}

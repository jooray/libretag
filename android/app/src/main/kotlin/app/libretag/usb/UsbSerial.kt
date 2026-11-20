package app.libretag.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Minimal USB serial over Android's USB host API. Enough for the tracker's text console: bulk
 * IN/OUT, line coding and DTR (Zephyr's CDC ACM only sends to a host that raised DTR).
 *
 * Two kinds: CDC-ACM (the nRF tags, Zephyr's own USB) and the Silicon Labs CP210x bridge of the
 * Heltec ESP32-S3 boards (docs/design/fw-wifi-esp32.md), which talk over UART0. On those boards
 * DTR and RTS drive the chip's reset and boot pins, so the app never touches them: opening the
 * port must not reset the tag (every reset counts toward its reset backoff).
 */
class UsbSerial private constructor(
    private val conn: UsbDeviceConnection,
    private val control: UsbInterface?,
    private val data: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val cp210x: Boolean = false,
) : SerialLink {
    /** a CP210x bridge (Heltec ESP32 boards) rather than CDC-ACM */
    val isCp210x: Boolean get() = cp210x
    override val wakeBeforeWrite: Boolean get() = cp210x
    override val minQuietMs: Int get() = 0


    override fun write(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val n = conn.bulkTransfer(outEp, bytes, off, minOf(outEp.maxPacketSize, bytes.size - off), 1000)
            if (n < 0) throw IOException("USB write failed")
            off += n
        }
    }

    /** Bytes read within timeoutMs, empty on timeout. */
    override fun read(timeoutMs: Int): ByteArray {
        val buf = ByteArray(maxOf(inEp.maxPacketSize, 64))
        val n = conn.bulkTransfer(inEp, buf, buf.size, timeoutMs)
        return if (n > 0) buf.copyOf(n) else ByteArray(0)
    }

    override fun close() {
        if (cp210x) runCatching { cpRequest(CP_IFC_ENABLE, 0) } else runCatching { setDtr(false) }
        control?.let { conn.releaseInterface(it) }
        conn.releaseInterface(data)
        conn.close()
    }

    private fun setDtr(on: Boolean) {
        val ifc = control ?: return
        // SET_CONTROL_LINE_STATE: DTR | RTS
        conn.controlTransfer(0x21, 0x22, if (on) 0x03 else 0x00, ifc.id, null, 0, 1000)
    }

    private fun setLineCoding(baud: Int) {
        val ifc = control ?: return
        val lc = byteArrayOf(
            baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte(),
            0, 0, 8, // 1 stop bit, no parity, 8 data bits
        )
        conn.controlTransfer(0x21, 0x20, 0, ifc.id, lc, lc.size, 1000)
    }

    /** CP210x vendor request to the interface (AN571). */
    private fun cpRequest(req: Int, value: Int, data: ByteArray? = null) {
        val n = conn.controlTransfer(0x41, req, value, this.data.id, data, data?.size ?: 0, 1000)
        if (n < 0) throw IOException("CP210x request $req failed")
    }

    private fun cpOpen(baud: Int) {
        cpRequest(CP_IFC_ENABLE, 1)
        cpRequest(CP_SET_LINE_CTL, 0x0800) // 8 data bits, no parity, 1 stop bit
        cpRequest(CP_SET_BAUDRATE, 0, byteArrayOf(baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(), (baud shr 24).toByte()))
        runCatching { cpRequest(CP_PURGE, 0x000F) }
        // no SET_MHS: DTR and RTS stay as they are, so the ESP32 is not reset
    }

    /**
     * 0.12: restart a Heltec ESP32 out of its ROM download mode (docs/design/tag-diagnosis.md 4.2):
     * DTR clear, so IO0 stays high (normal boot), RTS set for 100 ms (EN low), then cleared. The one
     * place where a reset is the point; the firmware counts it as power-on (PROTOCOL.md 2.5).
     * Needs a bench check with the board's auto-reset circuit.
     */
    fun cpReset() {
        if (!cp210x) return
        cpRequest(CP_SET_MHS, CP_MHS_MASK or CP_RTS)
        Thread.sleep(100)
        cpRequest(CP_SET_MHS, CP_MHS_MASK)
    }

    companion object {
        private const val CP_SET_MHS = 0x07
        /** SET_MHS: write both DTR (bit 8) and RTS (bit 9); values in bits 0 (DTR) and 1 (RTS) */
        private const val CP_MHS_MASK = 0x0300
        private const val CP_RTS = 0x0002

        /** USB IDs of the LibreTag tracker firmware (ZephCore tracker role). */
        const val VID = 0x2fe3
        const val PID_TRACKER = 0x0007
        /** Silicon Labs CP2102/CP2104 (Heltec WiFi LoRa 32 V3, Wireless Stick Lite V3) */
        const val VID_SILABS = 0x10c4
        const val PID_CP2102 = 0xea60

        private const val CP_IFC_ENABLE = 0x00
        private const val CP_SET_LINE_CTL = 0x03
        private const val CP_PURGE = 0x12
        private const val CP_SET_BAUDRATE = 0x1E

        fun isCdc(device: UsbDevice): Boolean = findData(device) != null

        fun isCp210x(device: UsbDevice): Boolean =
            device.vendorId == VID_SILABS && device.productId == PID_CP2102 && findVendorData(device) != null

        private fun findVendorData(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && bulkPair(it) != null }

        private fun findData(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && bulkPair(it) != null }

        private fun bulkPair(ifc: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until ifc.endpointCount) {
                val ep = ifc.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            return if (inEp != null && outEp != null) inEp to outEp else null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbSerial {
            if (isCp210x(device)) return openCp210x(manager, device)
            val data = findData(device) ?: throw IOException("not a USB serial device")
            val control = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            val (inEp, outEp) = bulkPair(data)!!
            val conn = manager.openDevice(device) ?: throw IOException("no permission for the USB device")
            control?.let { conn.claimInterface(it, true) }
            if (!conn.claimInterface(data, true)) {
                conn.close()
                throw IOException("USB interface busy")
            }
            return UsbSerial(conn, control, data, inEp, outEp).apply {
                setLineCoding(115200)
                setDtr(true)
            }
        }

        private fun openCp210x(manager: UsbManager, device: UsbDevice): UsbSerial {
            val data = findVendorData(device) ?: throw IOException("not a USB serial device")
            val (inEp, outEp) = bulkPair(data)!!
            val conn = manager.openDevice(device) ?: throw IOException("no permission for the USB device")
            if (!conn.claimInterface(data, true)) {
                conn.close()
                throw IOException("USB interface busy")
            }
            return UsbSerial(conn, null, data, inEp, outEp, cp210x = true).apply { cpOpen(115200) }
        }
    }
}

package app.libretag.usb.dfu

import java.io.ByteArrayOutputStream

/**
 * Framing of the legacy Nordic serial DFU (SDK 11) as the Adafruit nRF52 bootloader keeps it:
 * reliable HCI vendor packets with a CRC16, SLIP framed. Byte-compatible with adafruit-nrfutil
 * and host/tools/dfu.py (shared vectors in host/tests/vectors/dfu.json). docs/design/dfu.md.
 */
object Crc16 {
    /** CRC16-CCITT as Nordic's crc16_compute: poly 0x1021, init 0xFFFF, not reflected. */
    fun of(data: ByteArray, from: Int = 0, to: Int = data.size, init: Int = 0xFFFF): Int {
        var crc = init
        for (i in from until to) {
            crc = ((crc ushr 8) and 0xFF) or ((crc shl 8) and 0xFF00)
            crc = crc xor (data[i].toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc and 0xFFFF
    }
}

object Slip {
    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD

    fun escape(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(data.size + 16)
        for (b in data) when (b.toInt() and 0xFF) {
            END -> { o.write(ESC); o.write(ESC_END) }
            ESC -> { o.write(ESC); o.write(ESC_ESC) }
            else -> o.write(b.toInt())
        }
        return o.toByteArray()
    }

    /** Null on a broken escape (the frame is dropped, as the bootloader does). */
    fun unescape(data: ByteArray): ByteArray? {
        val o = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            if (b == ESC) {
                if (i + 1 >= data.size) return null
                when (data[i + 1].toInt() and 0xFF) {
                    ESC_END -> o.write(END)
                    ESC_ESC -> o.write(ESC)
                    else -> return null
                }
                i += 2
            } else { o.write(b); i++ }
        }
        return o.toByteArray()
    }
}

/** Splits a byte stream into SLIP frame bodies (unescaped, without the 0xC0 ends). */
class SlipReader {
    private val buf = ByteArrayOutputStream()
    private var inFrame = false

    fun feed(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (b in bytes) {
            if ((b.toInt() and 0xFF) == Slip.END) {
                // a 0xC0 both closes a frame and may open the next one
                if (inFrame && buf.size() > 0) Slip.unescape(buf.toByteArray())?.let { out += it }
                buf.reset()
                inFrame = true
            } else if (inFrame) {
                buf.write(b.toInt())
                if (buf.size() > 2048) { buf.reset(); inFrame = false } // garbage, wait for the next 0xC0
            }
        }
        return out
    }

    fun reset() { buf.reset(); inFrame = false }
}

object Hci {
    const val PACKET_TYPE = 14
    const val INIT_PACKET = 1
    const val START_PACKET = 3
    const val DATA_PACKET = 4
    const val STOP_DATA_PACKET = 5
    const val MODE_APPLICATION = 4
    const val DATA_CHUNK = 512

    fun header(seq: Int, length: Int, dip: Boolean = true, reliable: Boolean = true, type: Int = PACKET_TYPE): ByteArray {
        val h0 = (seq and 7) or (((seq + 1) and 7) shl 3) or (if (dip) 0x40 else 0) or (if (reliable) 0x80 else 0)
        val h1 = (type and 0x0F) or ((length and 0x0F) shl 4)
        val h2 = (length ushr 4) and 0xFF
        val h3 = (-(h0 + h1 + h2)) and 0xFF
        return byteArrayOf(h0.toByte(), h1.toByte(), h2.toByte(), h3.toByte())
    }

    /** One reliable vendor packet: 0xC0, escaped (header, payload, CRC16 little endian), 0xC0. */
    fun frame(seq: Int, payload: ByteArray): ByteArray {
        val body = header(seq, payload.size) + payload
        val crc = Crc16.of(body)
        val esc = Slip.escape(body + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte()))
        return byteArrayOf(Slip.END.toByte()) + esc + byteArrayOf(Slip.END.toByte())
    }

    /** The ack field (next sequence number the bootloader expects), null when the header is not valid. */
    fun ackNumber(body: ByteArray): Int? {
        if (body.size < 4) return null
        val sum = (0 until 4).sumOf { body[it].toInt() and 0xFF } and 0xFF
        if (sum != 0) return null
        return ((body[0].toInt() and 0xFF) ushr 3) and 7
    }

    private fun le32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

    fun startPayload(appSize: Int): ByteArray =
        le32(START_PACKET.toLong()) + le32(MODE_APPLICATION.toLong()) + le32(0) + le32(0) + le32(appSize.toLong())

    /** The init packet plus two bytes of padding, as adafruit-nrfutil sends it. */
    fun initPayload(dat: ByteArray): ByteArray = le32(INIT_PACKET.toLong()) + dat + byteArrayOf(0, 0)

    fun dataPayloads(image: ByteArray): List<ByteArray> =
        (image.indices step DATA_CHUNK).map { le32(DATA_PACKET.toLong()) + image.copyOfRange(it, minOf(it + DATA_CHUNK, image.size)) }

    fun stopPayload(): ByteArray = le32(STOP_DATA_PACKET.toLong())
}

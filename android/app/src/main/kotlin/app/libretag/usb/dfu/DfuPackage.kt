package app.libretag.usb.dfu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Flash layout of the Wio Tracker L1 build (zephcore/boards/common/nrf52_partitions_sdv7.dtsi). */
object FlashLayout {
    /** after MBR + S140 7.3.0: the bootloader's CODE_REGION_1_START, Zephyr's code_partition */
    const val APP_START = 0x27000
    /** end of code_partition; storage_partition (the two key pages) starts here, LittleFS at 0xD4000 */
    const val APP_END = 0xD0000
    /** an image this size or smaller never makes the single-bank bootloader erase the key pages */
    const val APP_MAX = APP_END - APP_START
    const val RAM_START = 0x20000000L
    const val RAM_END = 0x20040000L
}

/** dfu_init_packet_t of dfu_version 0.5 (what adafruit-nrfutil genpkg writes). */
data class InitPacket(val deviceType: Int, val deviceRev: Int, val appVersion: Long, val softDevices: List<Int>, val crc16: Int) {
    companion object {
        const val DEVICE_TYPE_ADAFRUIT = 0x0052

        fun parse(d: ByteArray): InitPacket? {
            fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
            if (d.size < 12) return null
            val n = u16(8)
            if (d.size < 10 + 2 * n + 2) return null
            val appVersion = (0 until 4).fold(0L) { acc, i -> acc or ((d[4 + i].toLong() and 0xFF) shl (8 * i)) }
            return InitPacket(u16(0), u16(2), appVersion, (0 until n).map { u16(10 + 2 * it) }, u16(10 + 2 * n))
        }
    }
}

class DfuPackageException(message: String) : Exception(message)

/**
 * A DFU .zip as host/tools/dfu.py packs it: manifest.json (application only), application.bin,
 * application.dat, libretag.json (board, version). [parse] makes every check the app relies on.
 */
class DfuPackage(val image: ByteArray, val initPacket: ByteArray, val version: String, val board: String) {
    val imageSha256: String get() = sha256Hex(image)

    companion object {
        const val BOARD = "wio_tracker_l1"

        fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** Problems with an application image for this board; empty when it is fine. */
        fun checkImage(image: ByteArray): List<String> {
            if (image.isEmpty()) return listOf("empty image")
            val out = mutableListOf<String>()
            if (image.size % 4 != 0) out += "size not a multiple of 4"
            if (image.size > FlashLayout.APP_MAX) out += "image larger than the application partition: the bootloader would erase the key pages"
            if (image.size >= 8) {
                fun u32(o: Int) = (0 until 4).fold(0L) { acc, i -> acc or ((image[o + i].toLong() and 0xFF) shl (8 * i)) }
                val sp = u32(0)
                val reset = u32(4)
                if (sp <= FlashLayout.RAM_START || sp > FlashLayout.RAM_END) out += "initial stack pointer not in RAM"
                val r = reset and 1L.inv()
                if (reset and 1L == 0L || r < FlashLayout.APP_START || r >= FlashLayout.APP_START + image.size) out += "reset vector outside the image"
            }
            return out
        }

        fun parse(zip: ByteArray): DfuPackage {
            val files = HashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) {
                        val b = z.readBytes()
                        if (b.size > 2 * FlashLayout.APP_MAX) throw DfuPackageException("${e.name}: too large")
                        files[e.name] = b
                    }
                }
            }
            val manifest = files["manifest.json"]?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject["manifest"]?.jsonObject }.getOrNull() }
                ?: throw DfuPackageException("no manifest")
            if (manifest.keys.any { it != "application" && it != "dfu_version" })
                throw DfuPackageException("the package also carries a SoftDevice or bootloader: refused")
            if (manifest["dfu_version"]?.jsonPrimitive?.doubleOrNull != 0.5) throw DfuPackageException("dfu_version is not 0.5")
            val app: JsonObject = manifest["application"]?.jsonObject ?: throw DfuPackageException("no application in the manifest")
            val bin = files[app["bin_file"]?.jsonPrimitive?.contentOrNull] ?: throw DfuPackageException("no application.bin")
            val dat = files[app["dat_file"]?.jsonPrimitive?.contentOrNull] ?: throw DfuPackageException("no application.dat")
            val ip = InitPacket.parse(dat) ?: throw DfuPackageException("bad init packet")
            if (ip.deviceType != InitPacket.DEVICE_TYPE_ADAFRUIT) throw DfuPackageException("device type is not 0x0052")
            if (ip.crc16 != Crc16.of(bin)) throw DfuPackageException("init packet CRC does not match the image")
            checkImage(bin).firstOrNull()?.let { throw DfuPackageException(it) }
            val lt = files["libretag.json"]?.let { runCatching { Json.parseToJsonElement(String(it)).jsonObject }.getOrNull() }
                ?: throw DfuPackageException("no libretag.json")
            val board = lt["board"]?.jsonPrimitive?.contentOrNull ?: ""
            if (board != BOARD) throw DfuPackageException("package is for board '$board'")
            val version = lt["version"]?.jsonPrimitive?.contentOrNull ?: throw DfuPackageException("no version")
            lt["size"]?.jsonPrimitive?.intOrNull?.let { if (it != bin.size) throw DfuPackageException("size does not match libretag.json") }
            lt["sha256"]?.jsonPrimitive?.contentOrNull?.let { if (!it.equals(sha256Hex(bin), true)) throw DfuPackageException("sha256 does not match libretag.json") }
            return DfuPackage(bin, dat, version, board)
        }
    }
}

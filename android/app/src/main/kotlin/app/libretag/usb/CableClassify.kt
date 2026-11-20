package app.libretag.usb

import app.libretag.data.Cable
import app.libretag.data.Tracker
import app.libretag.usb.dfu.DfuDevices

/**
 * What is on the cable, from the USB IDs alone (docs/design/tag-diagnosis.md 4.2), and to which
 * tag a bootloader or an ESP32 in download mode belongs. Pure, unit tested.
 */
object CableClassify {
    enum class Raw {
        NONE,
        /** an nRF52 tag in its UF2 / serial DFU bootloader */
        BOOTLOADER,
        /** the tracker firmware's own USB (CDC) */
        TAG_SERIAL,
        /** a CP2102 bridge (Heltec ESP32): firmware or the ROM loader behind it */
        CP2102,
        /** anything else */
        OTHER,
    }

    data class Device(val vid: Int, val pid: Int, val product: String? = null, val cdc: Boolean = false)

    fun classify(d: Device): Raw = when {
        DfuDevices.isBootloader(d.vid, d.pid, d.product) -> Raw.BOOTLOADER
        d.vid == UsbSerial.VID && d.pid == UsbSerial.PID_TRACKER -> Raw.TAG_SERIAL
        d.vid == UsbSerial.VID_SILABS && d.pid == UsbSerial.PID_CP2102 -> Raw.CP2102
        d.cdc -> Raw.TAG_SERIAL
        else -> Raw.OTHER
    }

    /** The most telling device on the cable (a bootloader wins). */
    fun classifyAll(devices: List<Device>): Raw {
        val all = devices.map { classify(it) }
        return listOf(Raw.BOOTLOADER, Raw.TAG_SERIAL, Raw.CP2102, Raw.OTHER).firstOrNull { it in all } ?: Raw.NONE
    }

    /** an ESP32 tag (Heltec): no nRF bootloader, the cable is a CP2102 */
    fun isEsp(t: Tracker): Boolean = t.wifiTag || t.board?.contains("esp32", ignoreCase = true) == true ||
        t.board?.contains("heltec", ignoreCase = true) == true

    const val DFU_RECENT_MS = 24 * 3600_000L

    /**
     * The tag a bootloader (or an ESP32 in download mode) on the cable belongs to: the one whose
     * update started here in the last 24 h; else the only overdue tag of that kind; else the only
     * tag of that kind. Null when none fits (the home screen shows a strip without a tag).
     */
    fun attribute(esp: Boolean, trackers: List<Tracker>, overdue: Set<String>, now: Long): Tracker? {
        val kind = trackers.filter { !it.isShared && isEsp(it) == esp }
        kind.filter { it.dfuStartedAt?.let { at -> now - at in 0..DFU_RECENT_MS } == true }
            .maxByOrNull { it.dfuStartedAt!! }?.let { return it }
        kind.filter { it.id in overdue }.singleOrNull()?.let { return it }
        return kind.singleOrNull()
    }

    /**
     * The cable state of one tag: on the console ([sessionTagId] over USB), or a bootloader or a
     * download-mode ESP32 attributed to it ([attributedId]).
     */
    fun cableFor(t: Tracker, raw: Raw, espDownload: Boolean, sessionTagId: String?, attributedId: String?): Cable = when {
        sessionTagId == t.id -> Cable.TAG
        raw == Raw.BOOTLOADER && attributedId == t.id -> Cable.BOOTLOADER
        raw == Raw.CP2102 && espDownload && attributedId == t.id -> Cable.ESP_DOWNLOAD
        else -> Cable.NONE
    }
}

/**
 * The ESP32 ROM loader's SLIP `SYNC` frame and its answer (esptool's serial protocol): a CP2102
 * that does not answer `status` may be sitting in download mode, where the firmware does not run.
 */
object EspSync {
    /** c0, direction 0, command 0x08 SYNC, size 36, checksum 0, 07 07 12 20 + 32 × 55, c0 */
    val FRAME: ByteArray = byteArrayOf(0xC0.toByte(), 0x00, 0x08, 0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0x07, 0x12, 0x20) +
        ByteArray(32) { 0x55 } + byteArrayOf(0xC0.toByte())

    /** an answer from the ROM: a SLIP frame starting c0 01 08 (response, SYNC) */
    fun isRomAnswer(b: ByteArray): Boolean {
        for (i in 0..b.size - 3) {
            if (b[i] == 0xC0.toByte() && b[i + 1] == 0x01.toByte() && b[i + 2] == 0x08.toByte()) return true
        }
        return false
    }
}

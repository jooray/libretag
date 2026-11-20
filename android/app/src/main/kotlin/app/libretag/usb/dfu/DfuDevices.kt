package app.libretag.usb.dfu

/**
 * Which USB device on the cable is what. The tag's firmware is Zephyr's 2fe3:0007; its bootloader
 * (Adafruit nRF52 bootloader, Seeed build for the Wio Tracker L1) enumerates with Seeed's VID and
 * a different PID, so Android asks for the USB permission again. The exact PIDs are not verified
 * on our hardware yet (docs/design/dfu.md, test plan): 0x1667/0x1668 are the Wio Tracker L1 IDs
 * PlatformIO/Meshtastic list; Adafruit's own boards use VID 0x239A.
 */
object DfuDevices {
    const val TRACKER_VID = 0x2fe3
    const val TRACKER_PID = 0x0007
    const val SEEED_VID = 0x2886
    const val ADAFRUIT_VID = 0x239A
    val WIO_L1_PIDS = setOf(0x1667, 0x1668)

    fun isTracker(vid: Int, pid: Int) = vid == TRACKER_VID && pid == TRACKER_PID

    /**
     * A tag in its bootloader (serial DFU over CDC, with or without the UF2 drive). [product] is the
     * USB product string when Android gives it ("TRACKER L1" on Seeed's build). Meshtastic on the same
     * board uses a Seeed VID too; the transfer then simply gets no answer and fails safely.
     */
    fun isBootloader(vid: Int, pid: Int, product: String?): Boolean = when (vid) {
        SEEED_VID -> pid in WIO_L1_PIDS || product?.contains("TRACKER L1", ignoreCase = true) == true
        ADAFRUIT_VID -> true
        else -> false
    }
}

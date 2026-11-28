package app.libretag.proto

/**
 * The 0.6 report fields the app stores (SPEC 11 decisions, PROTOCOL.md 2): when the tag will be
 * heard next, the intentional quiet and its reason, the used charge, the BLE summary and the Wi-Fi
 * layout. One place maps the decoder's [Report] onto them.
 */
data class ReportExtras(
    /** seconds until the tag will next be heard, null if the report doesn't say */
    val nextInS: Long? = null,
    val quiet: Boolean = false,
    /** "zone", "owner" or "parked" (0.7) */
    val quietReason: String? = null,
    /** used charge since the last full charge (mAh, lower bound), null = not counted */
    val energyMah: Int? = null,
    val bleDevices: Int? = null,
    val bleStable: Int? = null,
    /** Wi-Fi layout: one full BSSID (hex, 12 chars) with RSSI, AP count, and one 3-byte tail */
    val wifiSeed: String? = null,
    val wifiSeedRssi: Int? = null,
    val wifiApCount: Int? = null,
    val wifiTail: String? = null,
    val wifiTailRssi: Int? = null,
    /** a second full BSSID (hex), sent by a tag that never had a fix (PROTOCOL.md 2.2) */
    val wifiSeed2: String? = null,
    val wifiSeed2Rssi: Int? = null,
    /** a second tail (hex, 6 chars) in the repeater's slot */
    val wifiTail2: String? = null,
    val wifiTail2Rssi: Int? = null,
) {
    companion object {
        fun of(r: Report): ReportExtras = ReportExtras(
            nextInS = r.nextInS,
            quiet = r.quiet,
            quietReason = r.quietReason,
            energyMah = r.energyMah,
            bleDevices = r.ble?.devices,
            bleStable = r.ble?.stable,
            wifiSeed = r.wifi?.seedHex,
            wifiSeedRssi = r.wifi?.seedRssi,
            wifiApCount = r.wifi?.apCount,
            wifiTail = r.wifi?.tailHex,
            wifiTailRssi = r.wifi?.tailRssi,
            wifiSeed2 = r.wifi?.seed2Hex,
            wifiSeed2Rssi = r.wifi?.seed2?.let { r.wifi.seed2Rssi },
            wifiTail2 = r.wifi?.tail2Hex,
            wifiTail2Rssi = r.wifi?.tail2?.let { r.wifi.tail2Rssi },
        )
    }
}

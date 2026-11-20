package app.libretag.data

import app.libretag.proto.Mode
import app.libretag.proto.Report

/**
 * Entering and leaving a Wi-Fi zone, from the reports alone (pure, for tests). A tag without GPS
 * says nothing about where it is; it only goes quiet in a Wi-Fi zone. A quiet "zone" report without
 * a fix is a Wi-Fi zone (a GPS zone needs a fix of that wake-up, PROTOCOL.md 2.1); the next normal
 * IDLE report means the tag left it at that wake-up. Left together with the owner's device
 * (`QUIET_OWNER`): no alarm, as for GPS zones.
 *
 * 0.8.8: a normal report that may follow a reboot is no proof of leaving. The report has no boot
 * flag, but after a reset the counter jumps ahead (to the state's checkpoint, PROTOCOL.md 1), so
 * `n` is not `lastN + 1`. Such a report leaves the zone only when it carries the tag's Wi-Fi scan
 * (the tag saw access points and none of its zones). Without one the zone stays, and the next
 * report decides. Firmware 0.7.8 sends a boot inside a zone as a quiet report anyway.
 */
object WifiZoneTransit {
    /** the zone name when the tag has several Wi-Fi zones and the report can't say which */
    const val UNNAMED = "\u0001wifi"

    data class Step(val inZone: String?, val inZoneWifi: Boolean, val left: String? = null)

    /**
     * The next zone state after report [r], or null when the Wi-Fi rule says nothing (an old or a
     * GPS report, Track or Lost, where the tag reports whatever the place). [wifiTag]: the tag scans
     * Wi-Fi (a GPS tag's console report without a fix says nothing about a zone). [name]: the zone's
     * name if the app knows it.
     */
    fun step(inZone: String?, inZoneWifi: Boolean, r: Report, isNew: Boolean, wifiTag: Boolean, name: String?,
             n: Long = -1, lastN: Long = -1): Step? {
        if (!wifiTag || !isNew || r.hasFix || r.mode != Mode.IDLE) return null
        if (r.quiet && r.quietReason == "zone") return Step(name ?: UNNAMED, true)
        if (!inZoneWifi || inZone == null) return null
        // after a possible reboot (n jumped) and without a scan to say where it is: not yet
        if (r.wifi == null && lastN >= 0 && n != lastN + 1) return Step(inZone, true)
        val withOwner = r.quiet && r.quietOwner
        return Step(null, false, if (withOwner) null else inZone)
    }
}

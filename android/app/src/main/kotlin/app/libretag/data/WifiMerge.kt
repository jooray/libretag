package app.libretag.data

import app.libretag.proto.LibreTag
import app.libretag.proto.ReportExtras
import app.libretag.proto.WifiPacket
import app.libretag.wifi.Mac
import app.libretag.wifi.WifiAp
import app.libretag.wifi.WifiObservation
import app.libretag.wifi.WifiTail

/** The Wi-Fi networks a report carries, as the app keeps them (pure, for tests). */
object WifiMerge {
    /** The report's Wi-Fi layout: seed, tail and, from a tag that never had a fix, a second full BSSID. */
    fun ofReport(x: ReportExtras): WifiRec? {
        val seed = x.wifiSeed ?: return null
        return WifiRec(seed, x.wifiSeedRssi ?: -95, x.wifiApCount ?: 0,
            listOfNotNull(x.wifiTail?.let { WifiTailRec(it, x.wifiTailRssi ?: -95) },
                x.wifiTail2?.let { WifiTailRec(it, x.wifiTail2Rssi ?: -95) }),
            listOfNotNull(x.wifiSeed2?.let { WifiApRec(it, x.wifiSeed2Rssi ?: -95) }))
    }

    /**
     * A Wi-Fi packet (LOST, j = 4) adds its full BSSID and its tails to the report's networks. The
     * report's own seed stays the seed: it is the strongest one.
     */
    fun withPacket(cur: WifiRec?, x: WifiPacket): WifiRec? {
        val tails = x.tails.map { (tl, rs) -> WifiTailRec(LibreTag.bytesToHex(tl), rs) }
        val pktSeed = x.seed.takeIf { s -> s.any { it.toInt() != 0 } }?.let { WifiApRec(x.seedHex, x.seedRssi) }
        if (cur == null) {
            return pktSeed?.let { WifiRec(it.mac, it.rssi, x.apCount, tails) }
        }
        val others = (cur.others + listOfNotNull(pktSeed)).filter { it.mac != cur.seed }.distinctBy { it.mac }
        return cur.copy(apCount = maxOf(cur.apCount, x.apCount), tails = (cur.tails + tails).distinctBy { it.tail }, others = others)
    }

    /**
     * What a lookup gets for one report: the seed, the tails, and every further full BSSID (the
     * second seed of a tag that never had a fix, the Wi-Fi packet's seed in LOST). With two full
     * BSSIDs BeaconDB can answer from a single report.
     */
    fun observation(w: WifiRec?): WifiObservation? {
        w ?: return null
        val seed = Mac.normalize(w.seed) ?: return null
        val others = w.others.mapNotNull { o -> Mac.normalize(o.mac)?.takeIf { it != seed }?.let { WifiAp(it, o.rssi) } }.distinctBy { it.mac }
        return WifiObservation(WifiAp(seed, w.seedRssi), w.tails.mapNotNull { t -> Mac.normalizeTail(t.tail)?.let { WifiTail(it, t.rssi) } }, others)
    }
}

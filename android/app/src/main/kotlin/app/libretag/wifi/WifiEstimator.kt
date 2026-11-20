package app.libretag.wifi

import app.libretag.locate.Estimate
import app.libretag.locate.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WifiEstimate(
    val lat: Double,
    val lon: Double,
    val r68: Double,
    val r95: Double,
    /** APs (and a service fix) that went into the estimate */
    val apsUsed: Int,
    /** tails that matched more than one place and were left out */
    val ambiguousTails: Int,
    /** the repeater estimate disagreed badly; Wi-Fi was kept */
    val conflictWithRepeaters: Boolean = false,
)

/**
 * Position from matched Wi-Fi APs (docs/design/wifi-positioning.md, "Estimate in the app"): a grid around
 * the APs, log-distance path loss on RSSI where the tracker measured it (P0 = -40 dBm at 1 m,
 * n = 3, sigma = 8 dB), the service's AP position error, an epsilon-mixture so a router that moved
 * house cannot drag the estimate away, and floors (one AP is never better than ~50 m / 120 m).
 */
object WifiEstimator {
    const val P0 = -40.0
    const val N_EXP = 3.0
    const val SIGMA_DB = 8.0
    const val EPS = 0.1

    /** 1-sigma reach of an AP without RSSI (indoors to the street) */
    const val RANGE_M = 50.0

    /** reach beyond which an AP is not heard at all, even when loud */
    const val MAX_RANGE_M = 150.0

    /** an AP this far from the others' median is a moved router: dropped before the grid */
    const val OUTLIER_M = 1_500.0

    const val FLOOR68_ONE = 50.0
    const val FLOOR95_ONE = 120.0
    const val FLOOR68_MANY = 25.0
    const val FLOOR95_MANY = 60.0

    /** 2-D Gaussian: radius holding 68 % / 95 % is this many sigma */
    const val K68 = 1.5096
    const val K95 = 2.4477

    /** Tail matching plus [estimate]; the service's own fix (BeaconDB) counts as one more input. */
    fun fromObservation(obs: WifiObservation, lookup: LookupResult): WifiEstimate? {
        val m = TailMatcher.match(obs, lookup.aps)
        return estimate(m.matched, lookup.direct, m.ambiguous.size)
    }

    fun estimate(aps: List<MatchedAp>, direct: DirectFix? = null, ambiguousTails: Int = 0): WifiEstimate? {
        val pts = aps.map { it.ap.lat to it.ap.lon } + listOfNotNull(direct?.let { it.lat to it.lon })
        if (pts.isEmpty()) return null
        val medLat = median(pts.map { it.first })
        val medLon = median(pts.map { it.second })
        val keep = aps.filter { Geo.distanceM(medLat, medLon, it.ap.lat, it.ap.lon) <= OUTLIER_M }
        val dKeep = direct?.takeIf { Geo.distanceM(medLat, medLon, it.lat, it.lon) <= OUTLIER_M + it.accuracyM }
        val used = keep.size + (if (dKeep != null) 1 else 0)
        if (used == 0) return null

        val lat0 = (keep.map { it.ap.lat } + listOfNotNull(dKeep?.lat)).average()
        val lon0 = (keep.map { it.ap.lon } + listOfNotNull(dKeep?.lon)).average()
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        val ax = keep.map { (it.ap.lon - lon0) * kx }
        val ay = keep.map { (it.ap.lat - lat0) * ky }
        var reach = 0.0
        for (i in keep.indices) reach = max(reach, hypot(ax[i], ay[i]) + keep[i].ap.accuracyM)
        dKeep?.let { reach = max(reach, Geo.distanceM(lat0, lon0, it.lat, it.lon) + 2 * it.accuracyM) }
        val half = min(max(300.0, reach + 200.0), 3_000.0)
        val cell = max(5.0, half / 60.0)
        val n = (2 * half / cell).toInt() + 1
        val xs = DoubleArray(n) { -half + it * cell }

        // per AP: log-likelihood over the grid, normalised to a probability per cell, so a loud
        // AP (a narrow peak) and a silent one (a wide disc) weigh the same in the mixture
        val cells = n * n
        val eps = if (used >= 2) EPS else 0.0 // one input: nothing to disagree with
        val logp = DoubleArray(cells)
        val la = DoubleArray(cells)
        for (a in keep.indices) {
            val acc = max(keep[a].ap.accuracyM, 5.0)
            val rssi = keep[a].rssiDbm
            // range term: AP position error and how far Wi-Fi carries
            val sr2 = acc * acc + (if (rssi == null) RANGE_M * RANGE_M else MAX_RANGE_M * MAX_RANGE_M)
            for (j in 0 until n) for (i in 0 until n) {
                val d = hypot(xs[i] - ax[a], xs[j] - ay[a])
                var l = -0.5 * d * d / sr2
                if (rssi != null) {
                    val dd = max(d, 1.0)
                    // the AP's own position error, seen as spread in dB
                    val posDb = 10 * N_EXP / ln(10.0) * acc / max(dd, acc)
                    val z = rssi - (P0 - 10 * N_EXP * log10(dd))
                    l += -0.5 * z * z / (SIGMA_DB * SIGMA_DB + posDb * posDb)
                }
                la[j * n + i] = l
            }
            val m = la.max()
            var z = 0.0
            for (k in 0 until cells) z += exp(la[k] - m)
            // epsilon-mixture: the AP may simply be somewhere else now
            for (k in 0 until cells) logp[k] += ln((1 - eps) * exp(la[k] - m) / z + eps / cells)
        }
        dKeep?.let { f ->
            val fx = (f.lon - lon0) * kx
            val fy = (f.lat - lat0) * ky
            val sd = max(f.accuracyM, 10.0) / K68
            for (j in 0 until n) for (i in 0 until n) {
                val dx = xs[i] - fx
                val dy = xs[j] - fy
                logp[j * n + i] += -0.5 * (dx * dx + dy * dy) / (sd * sd)
            }
        }
        val mx = logp.max()
        var tot = 0.0
        val post = DoubleArray(n * n) { exp(logp[it] - mx).also { v -> tot += v } }
        var mxs = 0.0; var mys = 0.0
        for (j in 0 until n) for (i in 0 until n) {
            val p = post[j * n + i] / tot
            post[j * n + i] = p
            mxs += p * xs[i]; mys += p * xs[j]
        }
        val order = post.indices.sortedByDescending { post[it] }
        var cum = 0.0; var c68 = 0; var c95 = order.size
        for ((rank, idx) in order.withIndex()) {
            cum += post[idx]
            if (c68 == 0 && cum >= 0.68) c68 = rank + 1
            if (cum >= 0.95) { c95 = rank + 1; break }
        }
        val one = used == 1
        val r68 = max(sqrt(c68 * cell * cell / PI), if (one) FLOOR68_ONE else FLOOR68_MANY)
        val r95 = max(sqrt(c95 * cell * cell / PI), if (one) FLOOR95_ONE else FLOOR95_MANY)
        return WifiEstimate(lat0 + mys / ky, lon0 + mxs / kx, r68, max(r95, r68), used, ambiguousTails)
    }

    /**
     * Combines with the repeater estimate (SPEC 6.5), both read as circular Gaussians with
     * sigma = r68 / [K68]. Close enough (Mahalanobis distance <= [CONFLICT]): the product. Far
     * apart: Wi-Fi wins (an AP is heard over tens of metres, a repeater over kilometres) and the
     * conflict is flagged so the UI can say so.
     */
    fun combine(wifi: WifiEstimate, repeaters: Estimate?): WifiEstimate {
        if (repeaters == null) return wifi
        val sw = wifi.r68 / K68
        val sr = max(repeaters.r68, 1.0) / K68
        val d = Geo.distanceM(wifi.lat, wifi.lon, repeaters.lat, repeaters.lon)
        if (d / sqrt(sw * sw + sr * sr) > CONFLICT) return wifi.copy(conflictWithRepeaters = true)
        val ww = 1 / (sw * sw)
        val wr = 1 / (sr * sr)
        val s = sqrt(1 / (ww + wr))
        return wifi.copy(
            lat = (wifi.lat * ww + repeaters.lat * wr) / (ww + wr),
            lon = (wifi.lon * ww + repeaters.lon * wr) / (ww + wr),
            r68 = K68 * s,
            r95 = K95 * s,
            conflictWithRepeaters = false,
        )
    }

    const val CONFLICT = 3.0

    private fun median(v: List<Double>): Double = v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
}

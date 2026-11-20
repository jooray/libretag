package app.libretag.locate

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object Geo {
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val kx = 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return hypot((lon2 - lon1) * kx, (lat2 - lat1) * 110_540.0)
    }
}

/** One repeater the tracker heard or that heard the tracker. */
data class Anchor(val rep: Repeater, val snrDb: Double)

data class Estimate(
    val lat: Double,
    val lon: Double,
    val r68: Double,
    val r95: Double,
    /** cells inside the 95 % region: lat, lon, relative density 0..1 */
    val cells: List<Triple<Double, Double, Double>>,
    val cellM: Double,
    val anchors: List<Anchor>,
)

/**
 * Position without GPS from the repeaters around the tracker (SPEC 6.5). Log-distance path loss
 * on SNR with an unknown offset (transmit power, antennas, walls) marginalised, Gaussian
 * shadowing, and the last GPS fix as a prior that widens with its age. Checked on 2026-11-15:
 * with discover answers the true position was 1 km from the estimate, inside the 68 % region.
 * Repeaters differ a lot (a hilltop one carries 3x farther), so this is an area, not a point.
 */
object Estimator {
    private const val N_EXP = 3.0
    private const val SIGMA = 10.0
    private const val CELL = 100.0
    private const val HALF = 10_000.0

    fun estimate(anchors: List<Anchor>, lastFix: Pair<Double, Double>?, lastFixAgeS: Long?): Estimate? {
        if (anchors.isEmpty() && lastFix == null) return null
        val lat0 = if (anchors.isNotEmpty()) anchors.map { it.rep.lat }.average() else lastFix!!.first
        val lon0 = if (anchors.isNotEmpty()) anchors.map { it.rep.lon }.average() else lastFix!!.second
        val kx = 111_320.0 * cos(Math.toRadians(lat0))
        val ky = 110_540.0
        val n = (2 * HALF / CELL).toInt() + 1
        val xs = DoubleArray(n) { -HALF + it * CELL }
        val ax = anchors.map { (it.rep.lon - lon0) * kx }
        val ay = anchors.map { (it.rep.lat - lat0) * ky }
        val offsets = (0..30).map { 40.0 + 2.0 * it } // SNR at 1 m, dB
        val priorSigma = lastFixAgeS?.let { min(50_000.0, 300.0 + 20.0 * it) }
        val px = lastFix?.let { (it.second - lon0) * kx }
        val py = lastFix?.let { (it.first - lat0) * ky }

        val post = DoubleArray(n * n)
        val ll = DoubleArray(offsets.size)
        for (j in 0 until n) for (i in 0 until n) {
            val x = xs[i]; val y = xs[j]
            var best = Double.NEGATIVE_INFINITY
            for (k in offsets.indices) {
                var s = 0.0
                for (a in anchors.indices) {
                    val d = max(hypot(x - ax[a], y - ay[a]), 30.0)
                    val z = (anchors[a].snrDb - (offsets[k] - 10 * N_EXP * log10(d))) / SIGMA
                    s -= 0.5 * z * z
                }
                ll[k] = s
                if (s > best) best = s
            }
            var sum = 0.0
            for (k in offsets.indices) sum += exp(ll[k] - best)
            var lp = best + ln(sum)
            if (priorSigma != null && px != null && py != null) {
                val dz = hypot(x - px, y - py) / priorSigma
                lp -= 0.5 * dz * dz
            }
            post[j * n + i] = lp
        }
        val mx = post.max()
        var tot = 0.0
        for (i in post.indices) { post[i] = exp(post[i] - mx); tot += post[i] }
        var mxs = 0.0; var mys = 0.0
        for (j in 0 until n) for (i in 0 until n) {
            val p = post[j * n + i] / tot
            post[j * n + i] = p
            mxs += p * xs[i]; mys += p * xs[j]
        }
        val order = post.indices.sortedByDescending { post[it] }
        var cum = 0.0; var c68 = 0; var c95 = 0
        for ((rank, idx) in order.withIndex()) {
            cum += post[idx]
            if (c68 == 0 && cum >= 0.68) c68 = rank + 1
            if (cum >= 0.95) { c95 = rank + 1; break }
        }
        val pmax = post[order[0]]
        val cells = order.take(c95).map { idx ->
            val i = idx % n; val j = idx / n
            Triple(lat0 + xs[j] / ky, lon0 + xs[i] / kx, post[idx] / pmax)
        }
        return Estimate(
            lat = lat0 + mys / ky, lon = lon0 + mxs / kx,
            r68 = sqrt(c68 * CELL * CELL / PI), r95 = sqrt(c95 * CELL * CELL / PI),
            cells = cells, cellM = CELL, anchors = anchors,
        )
    }
}

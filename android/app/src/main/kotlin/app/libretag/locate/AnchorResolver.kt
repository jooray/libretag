package app.libretag.locate

import kotlin.math.min

/**
 * Turns the 2-byte repeater hashes in a report into repeaters with a position. The hash space is
 * small: across the public map (~55 000 repeaters) most prefixes match several repeaters, often
 * in different countries. A report from a Slovak city once resolved to a Dutch repeater because the
 * nearest-to-prior rule had no prior. Rules, in order:
 *
 *  1. With a prior (the report's last fix, or the newest fix the app has for this tracker),
 *     take the candidate nearest to it, but only within the distance the tracker could have
 *     travelled since (30 km + 40 m/s, capped); farther candidates are dropped.
 *  2. Without a prior, repeaters heard at the same time are close to each other (LoRa range):
 *     pick the combination with the smallest spread, seeded from each candidate of the hash
 *     with the fewest candidates; hashes with nothing within [CLUSTER_M] of the seed drop out.
 *  3. A single hash with several candidates and no prior is ambiguous: the freshest one is used
 *     and the caller says so, so nobody drives to the Netherlands.
 */
object AnchorResolver {
    const val CLUSTER_M = 60_000.0
    private const val REACH_BASE_M = 30_000.0
    private const val REACH_SPEED = 40.0 // m/s, a car on a motorway
    private const val REACH_MAX_M = 1_500_000.0

    data class Heard(val hash: String, val snrDb: Double)

    data class Result(
        val anchors: List<Anchor>,
        /** hashes not on the public map, or none of their candidates is plausible */
        val unresolved: Int,
        /** hashes resolved by a guess among several far-apart candidates */
        val ambiguous: Int,
    )

    fun reachM(priorAgeS: Long?): Double =
        min(REACH_MAX_M, REACH_BASE_M + REACH_SPEED * (priorAgeS ?: 0L).coerceAtLeast(0L))

    fun resolve(
        heard: List<Heard>,
        candidates: (String) -> List<Repeater>,
        prior: Pair<Double, Double>?,
        priorAgeS: Long?,
    ): Result {
        val cands = heard.map { it to candidates(it.hash) }
        if (prior != null) {
            val reach = reachM(priorAgeS)
            var missing = 0
            val anchors = cands.mapNotNull { (h, cs) ->
                cs.map { it to dist(prior, it) }.filter { it.second <= reach }.minByOrNull { it.second }
                    ?.let { Anchor(it.first, h.snrDb) } ?: run { missing++; null }
            }
            return Result(anchors, missing, 0)
        }

        val known = cands.filter { it.second.isNotEmpty() }
        var missing = cands.size - known.size
        if (known.isEmpty()) return Result(emptyList(), missing, 0)
        if (known.size == 1) {
            val (h, cs) = known[0]
            val pick = cs.maxBy { it.lastAdvert }
            val spread = cs.any { dist(pick, it) > CLUSTER_M }
            return Result(listOf(Anchor(pick, h.snrDb)), missing, if (spread) 1 else 0)
        }

        val seedIdx = known.indices.minBy { known[it].second.size }
        var best: List<Repeater?>? = null
        var bestScore = Double.MAX_VALUE
        for (seed in known[seedIdx].second) {
            val pick = known.mapIndexed { i, (_, cs) ->
                if (i == seedIdx) seed
                else cs.map { it to dist(seed, it) }.filter { it.second <= CLUSTER_M }.minByOrNull { it.second }?.first
            }
            val found = pick.count { it != null }
            // more repeaters explained beats a tighter cluster
            val score = -found * 1e9 + pick.filterNotNull().sumOf { dist(seed, it) }
            if (score < bestScore) { bestScore = score; best = pick }
        }
        val anchors = known.indices.mapNotNull { i -> best!![i]?.let { Anchor(it, known[i].first.snrDb) } }
        missing += known.size - anchors.size
        // a single repeater left in the cluster is no better than a guess
        val ambiguous = if (anchors.size == 1 && known[seedIdx].second.size > 1) 1 else 0
        return Result(anchors, missing, ambiguous)
    }

    private fun dist(p: Pair<Double, Double>, r: Repeater) = Geo.distanceM(p.first, p.second, r.lat, r.lon)
    private fun dist(a: Repeater, b: Repeater) = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
}

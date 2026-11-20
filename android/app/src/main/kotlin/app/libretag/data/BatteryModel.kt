package app.libretag.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One battery reading from a report. [mode] is the report's mode name: IDLE, TRACK, LISTEN or LOST. */
data class BatterySample(val timeMs: Long, val batteryPct: Int, val mode: String)

data class BatteryEstimate(
    /** at the current (IDLE) pace; [BatteryModel.MAX_REMAINING_HOURS] when [capped] */
    val remainingHours: Double,
    /** drop in percent per day of IDLE time (>= 0) */
    val ratePctPerDay: Double,
    /** IDLE days the rate was fitted on */
    val basedOnDays: Double,
    val confidence: Confidence,
    /** no measurable drop: the UI says "more than 6 months" */
    val capped: Boolean = false,
) {
    enum class Confidence { LOW, MEDIUM, HIGH }
}

/**
 * Battery life estimate (SPEC.md 11.10). Nothing until the reports cover two weeks. Then the drop
 * rate in IDLE is fitted robustly (Theil–Sen) on the last 14 days, since the last charge (a rise of
 * more than [CHARGE_JUMP] points). Stretches in TRACK, LISTEN or LOST are cut out: the drop across
 * them does not count and neither does their time, so a day of tracking does not make the normal
 * pace look worse. The percentage comes from voltage and is flat above ~80 %, which lowers confidence.
 */
object BatteryModel {
    const val DAY_MS = 86_400_000L
    const val MIN_DAYS = 14.0
    const val CHARGE_JUMP = 5
    private const val CHARGE_K = 3
    const val MAX_REMAINING_HOURS = 6 * 30.0 * 24 // "more than 6 months"
    private const val MIN_FIT_DAYS = 2.0
    private const val MIN_FIT_SAMPLES = 5
    private const val MIN_PAIR_DAYS = 0.25 // pairs closer than 6 h only measure the 1 % quantisation
    private const val MAX_FIT_POINTS = 400
    private const val IDLE = "IDLE"

    private fun valid(samples: List<BatterySample>) =
        samples.filter { it.batteryPct in 0..100 }.sortedBy { it.timeMs }

    /** Days between the first and the last usable sample. */
    fun daysOfData(samples: List<BatterySample>): Double {
        val v = valid(samples)
        if (v.size < 2) return 0.0
        return (v.last().timeMs - v.first().timeMs).toDouble() / DAY_MS
    }

    fun estimate(samples: List<BatterySample>): BatteryEstimate? {
        val v = valid(samples)
        if (daysOfData(v) < MIN_DAYS) return null
        val end = v.last().timeMs
        val recent = v.filter { it.timeMs >= end - (MIN_DAYS * DAY_MS).toLong() }

        // segments between charges, newest first
        val segments = mutableListOf<List<BatterySample>>()
        var start = 0
        var i = 1
        while (i < recent.size) {
            if (isCharge(recent, i)) {
                segments.add(0, recent.subList(start, i))
                // a slow charge seen in several reports: the new segment starts at the top
                var top = i
                while (top + 1 < recent.size && recent[top + 1].batteryPct > recent[top].batteryPct) top++
                start = top
                i = maxOf(top + 1, i + CHARGE_K)
            } else {
                i++
            }
        }
        segments.add(0, recent.subList(start, recent.size))

        for ((age, seg) in segments.withIndex()) {
            val fit = fit(seg) ?: continue
            val (rate, days, drop) = fit
            val current = recent.last().batteryPct.toDouble()
            val capped = rate <= current / MAX_REMAINING_HOURS * 24
            val hours = if (capped) MAX_REMAINING_HOURS else min(MAX_REMAINING_HOURS, current / rate * 24)
            var level = when {
                days >= 7 && drop >= 5 -> 2
                days >= 4 && drop >= 2 -> 1
                else -> 0
            }
            if (age > 0) level-- // the pace before the last charge
            if (seg.map { it.batteryPct }.average() > 80) level-- // flat part of the Li-ion curve
            if (capped && days >= 7) level = max(level, 1) // a flat line over a week is itself solid
            return BatteryEstimate(
                remainingHours = hours,
                ratePctPerDay = max(0.0, rate),
                basedOnDays = days,
                confidence = BatteryEstimate.Confidence.entries[level.coerceIn(0, 2)],
                capped = capped,
            )
        }
        return null
    }

    /**
     * A charge starts at [i] when the reading rises by more than [CHARGE_JUMP] points and the
     * median of up to [CHARGE_K] readings from [i] on is also more than [CHARGE_JUMP] points above
     * the median of up to [CHARGE_K] readings before it: one low outlier followed by a normal
     * reading is not a charge.
     */
    private fun isCharge(s: List<BatterySample>, i: Int): Boolean {
        fun med(l: List<BatterySample>) = l.map { it.batteryPct }.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
        val before = s.subList(maxOf(0, i - CHARGE_K), i)
        val after = s.subList(i, minOf(s.size, i + CHARGE_K))
        return s[i].batteryPct - s[i - 1].batteryPct > CHARGE_JUMP && med(after) - med(before) > CHARGE_JUMP
    }

    /**
     * Cumulative IDLE time vs cumulative IDLE drop, then the Theil–Sen slope. Returns
     * (rate %/day, IDLE days, total IDLE drop in points) or null if too little IDLE data.
     */
    private fun fit(seg: List<BatterySample>): Triple<Double, Double, Double>? {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var x = 0.0
        var y = 0.0
        var started = false
        for (i in 1 until seg.size) {
            val a = seg[i - 1]
            val b = seg[i]
            if (!a.mode.equals(IDLE, true) || !b.mode.equals(IDLE, true)) continue
            if (!started) {
                xs.add(x); ys.add(y); started = true
            }
            x += (b.timeMs - a.timeMs).toDouble() / DAY_MS
            y += (b.batteryPct - a.batteryPct).toDouble()
            xs.add(x); ys.add(y)
        }
        if (xs.size < MIN_FIT_SAMPLES || x < MIN_FIT_DAYS) return null
        val step = max(1, xs.size / MAX_FIT_POINTS)
        val px = xs.filterIndexed { i, _ -> i % step == 0 }
        val py = ys.filterIndexed { i, _ -> i % step == 0 }
        val slopes = ArrayList<Double>()
        for (i in px.indices) for (j in i + 1 until px.size) {
            val dx = px[j] - px[i]
            if (dx >= MIN_PAIR_DAYS) slopes.add((py[j] - py[i]) / dx)
        }
        if (slopes.isEmpty()) return null
        slopes.sort()
        val m = slopes.size
        val median = if (m % 2 == 1) slopes[m / 2] else (slopes[m / 2 - 1] + slopes[m / 2]) / 2
        return Triple(-median, x, abs(median * x))
    }
}

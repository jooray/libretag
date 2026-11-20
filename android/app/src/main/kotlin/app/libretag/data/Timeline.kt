package app.libretag.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A tag's reports by day for the timeline: reports are stations; long stretches without reports
 * are collapsed into one row, "quiet at Home" when the report before carried QUIET (intentional),
 * otherwise "no reports" (which the app can't explain). Pure, for tests.
 */
object Timeline {
    sealed interface Item {
        val at: Long
    }
    data class Report(val p: Position, /** index among the day's reports */ val index: Int) : Item { override val at get() = p.receivedAt }
    /** [to] null: still quiet now */
    data class Quiet(val from: Long, val to: Long?, val where: String?, val owner: Boolean, val parked: Boolean = false) : Item { override val at get() = to ?: Long.MAX_VALUE }
    data class Gap(val from: Long, val to: Long) : Item { override val at get() = to }

    data class Day(val date: LocalDate, val items: List<Item>) {
        val reports: List<Position> get() = items.filterIsInstance<Report>().map { it.p }
    }

    /** Days with reports, newest first (at most [maxDays]); items oldest first within a day. */
    fun days(t: Tracker, zone: ZoneId = ZoneId.systemDefault(), maxDays: Int = 14, now: Long = System.currentTimeMillis()): List<Day> {
        val ps = t.positions.filter { it.receivedAt > 0 }.sortedBy { it.receivedAt }
        if (ps.isEmpty()) return emptyList()
        val gapMs = maxOf(GAP_MIN_MS, (t.reportPeriodMin * 60_000L * 5) / 2)
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val byDay = linkedMapOf<LocalDate, MutableList<Item>>()
        val counters = HashMap<LocalDate, Int>()
        var prev: Position? = null
        for (p in ps) {
            val d = day(p.receivedAt)
            val list = byDay.getOrPut(d) { mutableListOf() }
            val a = prev
            if (a != null && p.receivedAt - a.receivedAt > gapMs) {
                list += if (a.quiet) Quiet(a.receivedAt, p.receivedAt, Status.quietZoneName(t, a), a.quietReason == "owner", a.quietReason == "parked")
                else Gap(a.receivedAt, p.receivedAt)
            }
            val i = counters.getOrDefault(d, 0)
            counters[d] = i + 1
            list += Report(p, i)
            prev = p
        }
        val last = ps.last()
        if (last.quiet) {
            val d = day(now)
            byDay.getOrPut(d) { mutableListOf() } += Quiet(last.receivedAt, null, Status.quietZoneName(t, last), last.quietReason == "owner", last.quietReason == "parked")
        }
        return byDay.entries.map { Day(it.key, it.value) }.sortedByDescending { it.date }.take(maxDays)
    }

    const val GAP_MIN_MS = 2 * 3600_000L
}

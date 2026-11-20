package app.libretag.nostr

/**
 * Private report query (0.8.0, docs/design/app-0.8.md "Report query"): instead of asking relays for
 * the 128–512 `#y` tags of each tag (which tells a relay which reports belong together, and to whose
 * IP address), the app fetches every kind 4243 since its last sync and matches them on the phone.
 *
 * Volume: one report ≈ 300 B on the wire. 1000 active tags reporting hourly ≈ 24 000 events a day
 * ≈ 7 MB. [MAX_PER_SYNC] (20 000 events, ≈ 6 MB) is about a day of such a network; a backlog bigger
 * than that is fetched by the old window query for the part not covered. When the live stream alone
 * exceeds [LIVE_MAX_PER_HOUR] (3000/h ≈ 72 000 a day ≈ 21 MB, too much for a phone on mobile data),
 * the app goes back to the window query for a day.
 */
object ReportSync {
    const val PAGE = 500
    const val MAX_PER_SYNC = 20_000
    const val LIVE_MAX_PER_HOUR = 3_000
    /** bridges stamp the time they heard a report and publish within seconds; late backlogs are covered by this overlap */
    const val OVERLAP_S = 3_600L
    const val WINDOW_MODE_MS = 24 * 3600_000L

    /**
     * [complete]: everything from `since` up to now was fetched. Otherwise the range
     * [since, uncoveredUntil] was not (the cap was reached) and goes to the window query.
     */
    data class Result(val events: List<NostrEvent>, val complete: Boolean, val uncoveredUntil: Long)

    /** Page backwards with `until` from [nowS] to [sinceS]: [fetch] is one REQ {kinds, since, until, limit} until EOSE. */
    suspend fun backlog(
        sinceS: Long, nowS: Long, fetch: suspend (since: Long, until: Long, limit: Int) -> List<NostrEvent>,
        page: Int = PAGE, cap: Int = MAX_PER_SYNC,
    ): Result {
        val seen = LinkedHashMap<String, NostrEvent>()
        var until = nowS
        while (true) {
            val got = fetch(sinceS, until, page)
            var added = 0
            for (e in got) if (seen.putIfAbsent(e.id, e) == null) added++
            if (got.size < page) return Result(seen.values.sortedBy { it.createdAt }, true, sinceS)
            val oldest = got.minOf { it.createdAt }
            // a full page of one second (or nothing new): step past it rather than loop
            val next = if (oldest >= until || added == 0) until - 1 else oldest
            if (next < sinceS) return Result(seen.values.sortedBy { it.createdAt }, true, sinceS)
            if (seen.size >= cap) return Result(seen.values.sortedBy { it.createdAt }, false, next)
            until = next
        }
    }

    /**
     * When a kind 4243 was heard (ms): (earliest, latest), latest null for an exact report. A bridge
     * that drained a report late from a companion node's queue publishes created_at = the drain (the
     * latest possible time, so a `since` query still sees it) and `["approx", "<earliest unix s>"]`
     * (0.8.2, PROTOCOL.md 4). A missing or malformed value gives an empty range at created_at that
     * still counts as approximate. Order-insensitive: bridge 0.3.0 had the two the other way round.
     */
    fun reportTimeMs(ev: NostrEvent): Pair<Long, Long?> {
        val tag = ev.tags.firstOrNull { it.isNotEmpty() && it[0] == "approx" } ?: return ev.createdAt * 1000 to null
        val v = tag.getOrNull(1)?.takeIf { it.length in 1..12 && it.all { c -> c in '0'..'9' } }?.toLong() ?: ev.createdAt
        return minOf(v, ev.createdAt) * 1000 to maxOf(v, ev.createdAt) * 1000
    }

    /** Live rate guard: events per rolling hour. */
    class Rate(private val windowMs: Long = 3600_000L) {
        private val times = ArrayDeque<Long>()
        @Synchronized fun add(nowMs: Long): Int {
            times.addLast(nowMs)
            while (times.isNotEmpty() && times.first() < nowMs - windowMs) times.removeFirst()
            return times.size
        }
    }
}

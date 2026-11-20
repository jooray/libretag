package app.libretag.wifi

import app.libretag.data.AppPrefs
import app.libretag.data.Position
import app.libretag.data.Tracker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * When the app looks a report's Wi-Fi networks up by itself (AppPrefs.wifiAuto). Pure: the caller
 * passes the clock. Every automatic query tells the service roughly where the tag is, so it runs
 * only for a new report heard just now, never for history, a relay backfill, or a report that
 * says the tag is quiet at one of its places (the app knows where that is, and a lookup would
 * tell the service where home is). The network budget is [WifiBudget].
 */
object WifiAutoLookup {
    /** a report older than this (a bridge's backlog, a relay copy fetched later) is not looked up */
    const val FRESH_MS = 30 * 60_000L

    /**
     * Whether [p] (just handled, [isNew] = its counter is above the tag's last) should be looked up
     * automatically under [mode] (AppPrefs.WIFI_AUTO_*). The cache and the budget come after this.
     */
    fun wanted(mode: String, service: WifiService, t: Tracker, p: Position, isNew: Boolean, now: Long): Boolean {
        if (service == WifiService.OFF || mode == AppPrefs.WIFI_AUTO_TAP) return false
        if (p.wifi == null || p.hasFix || t.wifiFixes.containsKey(p.n)) return false
        if (!isNew || p.via.startsWith("usb")) return false
        val heardAt = p.approxUntil ?: p.receivedAt
        if (now - heardAt !in 0 until FRESH_MS) return false
        if (quietAtPlace(p)) return false
        return when (mode) {
            AppPrefs.WIFI_AUTO_ALWAYS -> true
            AppPrefs.WIFI_AUTO_LOST -> isLost(p)
            else -> false
        }
    }

    /** the tag says it is quiet because it is at one of its places (GPS or Wi-Fi) */
    fun quietAtPlace(p: Position): Boolean = p.quiet && p.quietReason == "zone"

    fun isLost(p: Position): Boolean = p.mode == "LOST"

    /** The cache already answers the seed (a position, or "the service didn't know it"): no query, no budget. */
    fun answeredByCache(cache: WifiCache, obs: WifiObservation): Boolean = obs.seed?.let { cache.contains(it.mac) } == true
}

/**
 * The network budget of automatic lookups, persisted as [FILE] next to wifi-cache.json so a process
 * restart does not reset it. Manual lookups (a tap) are not counted. Lost reports skip the per-tag
 * gap and the daily cap, never the backoff.
 */
@Serializable
data class WifiBudget(
    /** tag id -> when its last automatic query went out (ms) */
    val lastByTag: Map<String, Long> = emptyMap(),
    /** when each automatic query of the last 24 h went out (ms), all tags */
    val queries: List<Long> = emptyList(),
    /** no automatic query before this (ms), after a failure or HTTP 429 */
    val backoffUntil: Long = 0,
    /** the current backoff step (ms), 0 = none */
    val backoffMs: Long = 0,
) {
    enum class Verdict { OK, BACKOFF, TAG_GAP, DAILY_CAP }

    fun check(tagId: String, now: Long, lost: Boolean): Verdict {
        if (now < backoffUntil && backoffUntil - now <= BACKOFF_MAX_MS) return Verdict.BACKOFF
        if (lost) return Verdict.OK
        lastByTag[tagId]?.let { if (now - it in 0 until TAG_GAP_MS) return Verdict.TAG_GAP }
        if (queries.count { now - it in 0 until WINDOW_MS } >= DAILY_CAP) return Verdict.DAILY_CAP
        return Verdict.OK
    }

    /** An automatic query for [tagId] went out at [now]. */
    fun spent(tagId: String, now: Long): WifiBudget = copy(
        lastByTag = (lastByTag + (tagId to now)).filterValues { now - it in 0 until WINDOW_MS },
        queries = queries.filter { now - it in 0 until WINDOW_MS } + now,
    )

    /** The query failed: wait 5 min, doubling up to 6 h; a rate limit (HTTP 429) waits at least 1 h. */
    fun failed(now: Long, rateLimited: Boolean = false): WifiBudget {
        var step = if (backoffMs <= 0) BACKOFF_MIN_MS else (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        if (rateLimited) step = step.coerceAtLeast(RATE_LIMIT_MIN_MS)
        return copy(backoffMs = step, backoffUntil = now + step)
    }

    /** The service answered (with or without a position): the backoff ends. */
    fun succeeded(): WifiBudget = if (backoffMs == 0L && backoffUntil == 0L) this else copy(backoffMs = 0, backoffUntil = 0)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FILE = "wifi-budget.json"
        /** one automatic query per tag per 10 min */
        const val TAG_GAP_MS = 10 * 60_000L
        /** automatic queries per 24 h for the whole app */
        const val DAILY_CAP = 60
        const val WINDOW_MS = 24 * 3600_000L
        const val BACKOFF_MIN_MS = 5 * 60_000L
        const val BACKOFF_MAX_MS = 6 * 3600_000L
        const val RATE_LIMIT_MIN_MS = 3600_000L
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Anything unreadable gives a fresh budget. */
        fun fromJson(s: String?): WifiBudget =
            if (s.isNullOrBlank()) WifiBudget() else runCatching { json.decodeFromString(serializer(), s) }.getOrDefault(WifiBudget())
    }
}

package app.libretag.data

/**
 * Since when the phone can receive reports (docs/design/tag-diagnosis.md 4.5): a relay connected
 * with the report subscription open, or the own node connected. A drop of every path clears
 * [upSince]; when a link is back, [upSince] is set only once the backfill query for the tags'
 * windows came back ([backfilled]). Pure; the app persists the three times in [AppPrefs].
 */
class PathLog(
    /** some path works, with the backfill done, since (ms); null = blind */
    var upSince: Long? = null,
    /** the start of the last stretch without a path (ms) */
    var blindFrom: Long? = null,
    /** when the last backfill came back (ms) */
    var backfillAt: Long? = null,
) {
    /** a link is up right now (backfill or not) */
    var linkUp: Boolean = false
        private set

    /** Called on every change (and on a tick) with whether any receive link is up. Returns true when something changed. */
    fun update(link: Boolean, now: Long): Boolean {
        if (link == linkUp) return false
        linkUp = link
        if (!link) {
            if (upSince != null || blindFrom == null) blindFrom = now
            upSince = null
        }
        return true
    }

    /** The relays answered the query for the tags' windows (or the own node replayed from its cursor). */
    fun backfilled(now: Long) {
        backfillAt = now
        if (linkUp && upSince == null) upSince = now
    }

    val blind: Boolean get() = upSince == null

    /** the phone could not receive at some point after [since] */
    fun wasBlind(since: Long): Boolean = upSince.let { it == null || it > since }
}

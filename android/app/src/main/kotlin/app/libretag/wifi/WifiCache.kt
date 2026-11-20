package app.libretag.wifi

import app.libretag.locate.Geo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AP positions already looked up, keyed by full MAC: fewer queries (each one tells the service
 * which AP the user cares about) and tails can be matched offline against the neighbours of a
 * seed seen before. LRU with a TTL (30 days by default); MACs the service did not know are
 * remembered too, so they are not asked again. Not thread-safe; the caller serialises access.
 */
class WifiCache(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX,
) {
    @Serializable
    private data class Entry(
        val mac: String,
        val lat: Double? = null,
        val lon: Double? = null,
        val acc: Double? = null,
        val t: Long,
    )

    @Serializable
    private data class Stored(val v: Int = 1, val entries: List<Entry>)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    val size: Int get() = map.size

    private fun fresh(e: Entry) = clock() - e.t < ttlMs // a clock that went back keeps entries

    private fun live(mac: String): Entry? {
        val m = Mac.normalize(mac) ?: return null
        val e = map[m] ?: return null
        if (!fresh(e)) { map.remove(m); return null }
        return e
    }

    /** The cached position; null when absent, expired, or known to be unknown. */
    fun get(mac: String): ApPosition? = live(mac)?.let { e ->
        if (e.lat == null || e.lon == null) null else ApPosition(e.mac, e.lat, e.lon, e.acc ?: 0.0)
    }

    /** The service was asked recently and did not know this MAC. */
    fun isKnownUnknown(mac: String): Boolean = live(mac)?.let { it.lat == null } ?: false

    /** Asked recently, with or without a position: no need to ask again. */
    fun contains(mac: String): Boolean = live(mac) != null

    fun put(ap: ApPosition) {
        val m = Mac.normalize(ap.mac) ?: return
        map[m] = Entry(m, ap.lat, ap.lon, ap.accuracyM, clock())
    }

    fun putUnknown(mac: String) {
        val m = Mac.normalize(mac) ?: return
        map[m] = Entry(m, t = clock())
    }

    /** Stores what a lookup returned (positions and unknowns). */
    fun putAll(r: LookupResult) {
        r.aps.forEach { put(it) }
        r.unknown.forEach { putUnknown(it) }
    }

    /**
     * As [putAll], and a service that answers with a position only (BeaconDB) has it kept under
     * the observation's [seedMac], so the same seed is answered from the cache next time.
     */
    fun putAll(r: LookupResult, seedMac: String?) {
        putAll(r)
        val d = r.direct ?: return
        if (seedMac != null && get(seedMac) == null) put(ApPosition(seedMac, d.lat, d.lon, d.accuracyM))
    }

    /** Fresh positions within [radiusM] of a point, e.g. the neighbours of a cached seed. */
    fun near(lat: Double, lon: Double, radiusM: Double): List<ApPosition> =
        map.values.filter { it.lat != null && it.lon != null && fresh(it) && Geo.distanceM(lat, lon, it.lat, it.lon) <= radiusM }
            .map { ApPosition(it.mac, it.lat!!, it.lon!!, it.acc ?: 0.0) }

    /** Drops expired entries. */
    fun prune() {
        map.entries.removeIf { !fresh(it.value) }
    }

    fun clear() = map.clear()

    /** JSON, least recently used first, expired entries left out. */
    fun toJson(): String = json.encodeToString(Stored.serializer(), Stored(entries = map.values.filter { fresh(it) }))

    companion object {
        const val DEFAULT_TTL_MS = 30L * 24 * 3600 * 1000
        const val DEFAULT_MAX = 5_000
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Restores a cache; anything unreadable gives an empty one. */
        fun fromJson(
            s: String?,
            clock: () -> Long,
            ttlMs: Long = DEFAULT_TTL_MS,
            maxEntries: Int = DEFAULT_MAX,
        ): WifiCache {
            val c = WifiCache(clock, ttlMs, maxEntries)
            if (s.isNullOrBlank()) return c
            try {
                json.decodeFromString(Stored.serializer(), s).entries.forEach { e ->
                    val m = Mac.normalize(e.mac) ?: return@forEach
                    val entry = e.copy(mac = m)
                    if (c.fresh(entry)) c.map[m] = entry
                }
            } catch (e: Exception) {
                c.map.clear()
            }
            return c
        }
    }
}

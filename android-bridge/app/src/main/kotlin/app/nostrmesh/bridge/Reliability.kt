package app.nostrmesh.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * An event no relay accepted yet (port of the outbox in host/src/nostrmesh/ledger.py). [event] is
 * the signed event as JSON text; [key] the ledger key marked published once a relay takes it.
 */
@Serializable
data class OutboxEntry(
    val id: String,
    val event: String,
    val key: String,
    val profile: String,
    val expires: Long,
    val nextTry: Long,
    val tries: Int = 0,
)

/**
 * Events waiting for a relay, retried with the Python bridge's backoff (15, 15, 30, 60 min) until
 * they expire (14 d, capped by the event's own `expiration`). Thread-safe; kept in [BridgeState].
 */
class Outbox(private val ttlMs: Long = TTL_MS, private val cap: Int = CAP) {
    private val entries = LinkedHashMap<String, OutboxEntry>()
    private val inFlight = HashSet<String>()

    val size: Int @Synchronized get() = entries.size

    @Synchronized
    fun contains(id: String) = id in entries

    /** Keeps [event] until [done]; the first retry is [BACKOFF_MS] later. Returns the expiry used. */
    @Synchronized
    fun put(id: String, event: String, key: String, profile: String, now: Long, eventExpiresMs: Long?): Long {
        val exp = minOf(now + ttlMs, eventExpiresMs ?: Long.MAX_VALUE)
        if (id !in entries) {
            entries[id] = OutboxEntry(id, event, key, profile, exp, now + BACKOFF_MS[0])
            while (entries.size > cap) entries.remove(entries.keys.first())
        }
        inFlight += id
        return exp
    }

    @Synchronized
    fun done(id: String) {
        entries.remove(id)
        inFlight -= id
    }

    /** A retry failed: next try after the backoff. */
    @Synchronized
    fun failed(id: String, now: Long) {
        inFlight -= id
        val e = entries[id] ?: return
        val tries = e.tries + 1
        entries[id] = e.copy(tries = tries, nextTry = now + BACKOFF_MS[minOf(tries, BACKOFF_MS.size - 1)])
    }

    /** The first attempt failed: the entry waits for its first retry. */
    @Synchronized
    fun release(id: String) { inFlight -= id }

    /** Drops expired entries (returned as the second value) and claims the due ones for a retry. */
    @Synchronized
    fun due(now: Long, limit: Int = 50): Pair<List<OutboxEntry>, List<OutboxEntry>> {
        val expired = entries.values.filter { it.expires <= now }
        expired.forEach { entries.remove(it.id); inFlight -= it.id }
        val due = entries.values.filter { it.nextTry <= now && it.id !in inFlight }.sortedBy { it.nextTry }.take(limit)
        due.forEach { inFlight += it.id }
        return due to expired
    }

    @Synchronized
    fun export(): List<OutboxEntry> = entries.values.toList()

    @Synchronized
    fun restore(list: List<OutboxEntry>, now: Long) {
        entries.clear()
        inFlight.clear()
        list.filter { it.expires > now }.forEach { entries[it.id] = it }
    }

    companion object {
        /** The pool's own retries take about 13 min, so the first outbox retry is 15 min later. */
        val BACKOFF_MS = longArrayOf(900_000, 900_000, 1_800_000, 3_600_000)
        const val TTL_MS = 14 * 86400_000L
        const val CAP = 2_000

        /** `expiration` tag of an event in ms, or null. */
        fun expirationMs(event: JsonObject): Long? =
            (event["tags"] as? JsonArray)?.firstNotNullOfOrNull { t ->
                val a = t as? JsonArray ?: return@firstNotNullOfOrNull null
                if (a.size >= 2 && (a[0] as? JsonPrimitive)?.contentOrNull == "expiration") {
                    a[1].jsonPrimitive.contentOrNull?.toLongOrNull()?.times(1000)
                } else null
            }
    }
}

/**
 * Radio silence watchdog (host bridge: `[watchdog] rx_silence_s`, exit 3 for the supervisor). On
 * Android the service restarts the Local API client instead. Activity is any datagram or any
 * message from the server, so a quiet valley with a live link does not count as silence. After a
 * reconnect the next one is due only after another full [silenceMs].
 */
class Watchdog(var silenceMs: Long = SILENCE_MS, startedAt: Long) {
    private var baseline = startedAt
    var reconnects = 0
        private set

    /** True (once per silent period) when nothing came from the radio for [silenceMs]. */
    @Synchronized
    fun due(now: Long, lastActivity: Long): Boolean {
        if (silenceMs <= 0) return false
        val last = maxOf(lastActivity, baseline)
        if (now - last <= silenceMs) return false
        baseline = now
        reconnects++
        return true
    }

    @Synchronized
    fun silenceMs(now: Long, lastActivity: Long) = now - maxOf(lastActivity, baseline)

    companion object {
        const val SILENCE_MS = 30 * 60_000L
    }
}

package app.libretag.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Which bridges hear a tag (PROTOCOL.md 4.1): one relay query for receipts (kind 4245) of its
 * recent reports, then one for the announcements (kind 38244) of their authors. Announcements are
 * cached, so a claim (kind 24244) can be checked against them. Any failure gives an empty view,
 * so entries go out open: a lookup never blocks a command.
 */
class BridgeLookup(
    private val query: suspend (List<JsonObject>, Long) -> List<JsonObject>,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** What the relays said: build targets for untimed entries or for a timed `via`. */
    class View(val receipts: List<Receipt>, val announcements: List<Announcement>, private val recent: Map<String, Long>) {
        fun untimed(nowS: Long): Targets = BridgeTargets.untimed(receipts, announcements, recent, nowS)
        fun timed(via: String, nowS: Long): Targets = BridgeTargets.timed(receipts, announcements, via, nowS)

        companion object {
            val EMPTY = View(emptyList(), emptyList(), emptyMap())
        }
    }

    private val known = ConcurrentHashMap<String, List<Announcement>>()
    /** pubkeys asked about with no announcement found: when (s), so a claim spammer costs one query per key */
    private val unknown = ConcurrentHashMap<String, Long>()

    private fun remember(anns: List<Announcement>) {
        anns.groupBy { it.pubkey }.forEach { (pk, list) ->
            known[pk] = list.sortedByDescending { it.expires }.distinctBy { it.network }
            unknown.remove(pk)
        }
    }

    private fun validKnown(pk: String, nowS: Long): List<Announcement> =
        known[pk]?.filter { it.expires > nowS }.orEmpty()

    @Volatile private var lastView: View? = null

    /** The last view the relays gave (P6: a late entry goes out at once with the targets the app already has). */
    fun cachedView(): View? = lastView

    /** Receipts for [recent] (y -> report counter) and the announcements of their authors. */
    suspend fun view(recent: Map<String, Long>, timeoutMs: Long = QUERY_TIMEOUT_MS): View {
        if (recent.isEmpty()) return View.EMPTY
        return viewOf(recent, timeoutMs).also { if (it.receipts.isNotEmpty()) lastView = it }
    }

    /**
     * The announced bridges with a receipt for report [y], asked for at most [timeoutMs] (a live ACK
     * entry names the bridge that heard the copy only when the receipt is there within 500 ms).
     */
    suspend fun receiptAuthors(y: String, timeoutMs: Long): List<String> = try {
        val nowS = now()
        (withTimeoutOrNull(timeoutMs) {
            query(listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(BridgeTargets.RECEIPT_KIND)) })
                put("#y", buildJsonArray { add(JsonPrimitive(y)) })
            }), timeoutMs)
        } ?: emptyList()).mapNotNull { o -> NostrEvent.fromJson(o)?.let { BridgeTargets.parseReceipt(it, nowS) } }
            .map { it.pubkey }.distinct().filter { validKnown(it, nowS).isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** A bridge with a valid announcement (for a blind Lost entry), preferring one that heard the tag lately. */
    fun anyKnownBridge(): String? {
        val nowS = now()
        lastView?.receipts?.map { it.pubkey }?.firstOrNull { validKnown(it, nowS).isNotEmpty() }?.let { return it }
        return known.keys.firstOrNull { validKnown(it, nowS).isNotEmpty() }
    }

    /**
     * The bridges that heard [ys] (receipts), with their newest announcement asked again (not from
     * the cache): what "bridge offline" needs (SPEC.md 3.2, R13).
     */
    suspend fun hearers(ys: Collection<String>, timeoutMs: Long = QUERY_TIMEOUT_MS): List<Announcement> {
        if (ys.isEmpty()) return emptyList()
        return try {
            val nowS = now()
            val receipts = query(listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(BridgeTargets.RECEIPT_KIND)) })
                put("#y", buildJsonArray { ys.forEach { add(JsonPrimitive(it)) } })
            }), timeoutMs).mapNotNull { o -> NostrEvent.fromJson(o)?.let { BridgeTargets.parseReceipt(it, nowS) } }
            val authors = receipts.map { it.pubkey }.distinct()
            if (authors.isEmpty()) return emptyList()
            val anns = fetchAnnouncements(authors, timeoutMs, nowS)
            remember(anns)
            anns.groupBy { it.pubkey }.map { (_, l) -> l.maxBy { it.createdAt } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun viewOf(recent: Map<String, Long>, timeoutMs: Long): View {
        return try {
            val nowS = now()
            val receipts = query(listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(BridgeTargets.RECEIPT_KIND)) })
                put("#y", buildJsonArray { recent.keys.forEach { add(JsonPrimitive(it)) } })
            }), timeoutMs).mapNotNull { o -> NostrEvent.fromJson(o)?.let { BridgeTargets.parseReceipt(it, nowS) } }
                .distinctBy { it.pubkey to it.y }
            val authors = receipts.map { it.pubkey }.distinct()
            if (authors.isEmpty()) return View(emptyList(), emptyList(), recent)
            remember(fetchAnnouncements(authors, timeoutMs, nowS))
            View(receipts, authors.flatMap { validKnown(it, nowS) }, recent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            View.EMPTY
        }
    }

    /** What the relays had for counters the app has not opened: receipts with announcements, and the reports themselves. */
    class Ahead(val receipts: List<Receipt>, val announcements: List<Announcement>, val reports: List<NostrEvent>)

    /** One query for receipts (4245) and reports (4243) of [ys], then the announcements of the receipts' authors. */
    suspend fun ahead(ys: Collection<String>, timeoutMs: Long = QUERY_TIMEOUT_MS): Ahead {
        if (ys.isEmpty()) return Ahead(emptyList(), emptyList(), emptyList())
        return try {
            val nowS = now()
            val evs = query(listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(BridgeTargets.RECEIPT_KIND)); add(JsonPrimitive(4243)) })
                put("#y", buildJsonArray { ys.forEach { add(JsonPrimitive(it)) } })
            }), timeoutMs).mapNotNull { NostrEvent.fromJson(it) }
            val receipts = evs.mapNotNull { BridgeTargets.parseReceipt(it, nowS) }.distinctBy { it.pubkey to it.y }
            val reports = evs.filter { it.kind == 4243 }
            val authors = receipts.map { it.pubkey }.distinct()
            val missing = authors.filter { validKnown(it, nowS).isEmpty() }
            if (missing.isNotEmpty()) remember(fetchAnnouncements(missing, timeoutMs, nowS))
            Ahead(receipts, authors.flatMap { validKnown(it, nowS) }, reports)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Ahead(emptyList(), emptyList(), emptyList())
        }
    }

    /** The key has a valid announcement (cached, or asked of the relays once). */
    suspend fun isBridge(pubkey: String, timeoutMs: Long = QUERY_TIMEOUT_MS): Boolean {
        val nowS = now()
        if (validKnown(pubkey, nowS).isNotEmpty()) return true
        if (!isLowerHex(pubkey, 32)) return false
        unknown[pubkey]?.let { if (nowS - it < UNKNOWN_RECHECK_S) return false }
        unknown[pubkey] = nowS
        if (unknown.size > UNKNOWN_MAX) unknown.entries.removeIf { nowS - it.value >= UNKNOWN_RECHECK_S }
        return try {
            val anns = fetchAnnouncements(listOf(pubkey), timeoutMs, nowS)
            remember(anns)
            validKnown(pubkey, nowS).isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unknown.remove(pubkey) // relays failed: ask again next time
            false
        }
    }

    private suspend fun fetchAnnouncements(authors: List<String>, timeoutMs: Long, nowS: Long): List<Announcement> =
        (withTimeoutOrNull(timeoutMs + 1_000) {
            query(listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(BridgeTargets.ANNOUNCE_KIND)) })
                put("#k", buildJsonArray { add(JsonPrimitive("4244")) })
                put("authors", buildJsonArray { authors.forEach { add(JsonPrimitive(it)) } })
            }), timeoutMs)
        } ?: emptyList())
            .mapNotNull { o -> NostrEvent.fromJson(o)?.let { BridgeTargets.parseAnnouncement(it, nowS) } }
            .filter { it.pubkey in authors }

    companion object {
        const val QUERY_TIMEOUT_MS = 5_000L
        const val UNKNOWN_RECHECK_S = 600L
        private const val UNKNOWN_MAX = 2_000
    }
}

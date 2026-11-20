package app.libretag.nostr

import app.libretag.proto.LibreTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Anchors (PROTOCOL.md 4.2): kind 4244 events with empty content, signed with sk_n, that name the
 * tags x(n, 0) and x(n, 1) of report n before the tag sends it. A bridge locks a key for a report
 * only when an event of that key reached it [ANCHOR_LEAD_S] before the report, so the app keeps
 * anchors published for the next reports of every tag it can command, whether a command waits or not.
 */
object AnchorPlan {
    /** reports ahead, after the newest one the app has */
    const val AHEAD = 8
    const val TTL_MIN_S = 2 * 3600L
    /** a quiet tag's longest heartbeat (24 h) and an hour */
    const val TTL_MAX_S = 26 * 3600L
    const val TTL_UNKNOWN_S = 12 * 3600L
    /** created_at is spread over this many seconds before now */
    const val SPREAD_S = 300
    /** the bridges' default anchor_lead_s: an anchor younger than this before a report locks nothing */
    const val ANCHOR_LEAD_S = 60L

    /** Three report intervals ([nextInS] of the newest report), within [TTL_MIN_S] .. [TTL_MAX_S]. */
    fun ttlS(nextInS: Long?): Long =
        nextInS?.takeIf { it > 0 }?.let { (3 * it).coerceIn(TTL_MIN_S, TTL_MAX_S) } ?: TTL_UNKNOWN_S

    /**
     * Counters to keep anchored after report [lastN]: the next [AHEAD], and the start of the next
     * epoch (a reboot with a damaged state, or a quiet stretch of 12 h or more, jumps there,
     * PROTOCOL.md 2.1 rule 1).
     */
    fun wanted(lastN: Long): List<Long> {
        val base = maxOf(lastN, -1L)
        val out = ((base + 1)..(base + AHEAD)).toMutableList()
        val nextEpoch = (LibreTag.epoch(maxOf(base, 0L)) + 1) * LibreTag.EPOCH_REPORTS
        if (nextEpoch !in out) out += nextEpoch
        return out
    }

    /** Of [wanted], those without an anchor that lives at least half of [ttlS] more ([published]: n -> expiration). */
    fun due(wanted: List<Long>, published: Map<Long, Long>, nowS: Long, ttlS: Long): List<Long> =
        wanted.filter { n -> published[n].let { it == null || it - nowS < ttlS / 2 } }

    /** The anchor's tags: ["v","2"], the y tags of both copies, the expiration, and the targeting tags. */
    fun tags(ys: List<String>, expS: Long, targets: Targets): List<List<String>> =
        listOf(listOf("v", "2")) + ys.map { listOf("y", it) } + listOf(listOf("expiration", expS.toString())) +
            BridgeTargets.targetTags(targets)

    /** The anchor for report [n] of a tag whose command chain at n's epoch is [cmdE], view chain [viewE]. */
    fun event(cmdE: ByteArray, viewE: ByteArray, n: Long, createdAtS: Long, expS: Long, targets: Targets): NostrEvent {
        val ys = (0..1).map { j -> LibreTag.bytesToHex(LibreTag.reportTag(viewE, n, j)) }
        return NostrEvent.sign(LibreTag.mailboxSk(cmdE, n), createdAtS, LibreTag.NOSTR_COMMAND_KIND, tags(ys, expS, targets), "")
    }
}

@Serializable
data class AnchorRec(val tracker: String, val n: Long, val exp: Long)

/** Which anchors this app published (tracker, n, expiration), so it renews them instead of republishing each sync. */
class AnchorBook(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = mutableListOf<AnchorRec>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(ListSerializer(AnchorRec.serializer()), f.readText()) }.getOrNull()?.let { list.addAll(it) }
        }
    }

    /** n -> the latest expiration of an anchor for it */
    @Synchronized
    fun published(tracker: String, nowS: Long): Map<Long, Long> =
        list.filter { it.tracker == tracker && it.exp > nowS }.groupBy { it.n }.mapValues { e -> e.value.maxOf { it.exp } }

    @Synchronized
    fun record(tracker: String, n: Long, exp: Long, nowS: Long) {
        list.removeAll { it.exp <= nowS || (it.tracker == tracker && it.n == n) }
        list.add(AnchorRec(tracker, n, exp))
        save()
    }

    @Synchronized
    fun forget(tracker: String) {
        if (list.removeAll { it.tracker == tracker }) save()
    }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(AnchorRec.serializer()), list))
        tmp.renameTo(f)
    }
}

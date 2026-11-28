package app.libretag.nostr

import app.libretag.proto.LibreTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Anchors (PROTOCOL.md 4.2, 4.3): kind 4244 events with empty content, signed with sk_n, that name the
 * tags x(n, j) of every copy on air (j = 0, 1, 9, 10, 11) of report n before the tag sends it. A bridge locks a key for a report
 * only when an event of that key reached it [ANCHOR_LEAD_S] before the report, so the app keeps
 * anchors published for the next reports of every tag it can command, whether a command waits or not.
 */
object AnchorPlan {
    /** reports ahead, after the newest one the app has: 16 in IDLE, 24 in TRACK and LOST (P9) */
    const val AHEAD = 16
    const val AHEAD_MOVING = 24
    const val TTL_MIN_S = 2 * 3600L
    /** up to 50 h (PROTOCOL.md 4.3) */
    const val TTL_MAX_S = 50 * 3600L
    const val TTL_UNKNOWN_S = 12 * 3600L
    /** created_at is spread over this many seconds before now */
    const val SPREAD_S = 300
    /** the bridges' default anchor_lead_s: an anchor younger than this before a report locks nothing */
    const val ANCHOR_LEAD_S = 60L

    /**
     * Long enough to cover the counters ahead: [ahead] report periods ([periodS], the report's
     * next_in or the IDLE period), within [TTL_MIN_S] .. [TTL_MAX_S].
     */
    fun ttlS(periodS: Long?, ahead: Int = AHEAD): Long =
        periodS?.takeIf { it > 0 }?.let { (ahead * it).coerceIn(TTL_MIN_S, TTL_MAX_S) } ?: TTL_UNKNOWN_S

    /**
     * Counters to keep anchored after report [lastN]: the next [AHEAD] ([AHEAD_MOVING] in TRACK and
     * LOST), the two counters a reboot lands on (the next two multiples of 16 above the last used,
     * firmware 0.10.0 checkpoints), and the start of the next epoch (a quiet stretch of 12 h or more).
     */
    fun wanted(lastN: Long, moving: Boolean = false): List<Long> {
        val base = maxOf(lastN, -1L)
        val out = ((base + 1)..(base + if (moving) AHEAD_MOVING else AHEAD)).toMutableList()
        val c16 = (maxOf(base, 0L) / 16 + 1) * 16
        for (c in listOf(c16, c16 + 16)) if (c !in out) out += c
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
        val ys = app.libretag.proto.QuietMesh.J_ON_AIR.map { j -> LibreTag.bytesToHex(LibreTag.reportTag(viewE, n, j)) }
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

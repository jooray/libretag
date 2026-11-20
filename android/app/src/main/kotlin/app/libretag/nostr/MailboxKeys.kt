package app.libretag.nostr

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The command mailbox events (kind 4244) this app published, so a pending command can be
 * cancelled with a NIP-09 deletion signed by the same key (PROTOCOL.md 4). Since the signed
 * mailbox (4.2) that key is sk_n of report [MailboxKey.n], derived again when needed; [MailboxKey.sk]
 * is empty for new entries (older app versions stored a random key per event). Kept in the app's
 * private no-backup storage while the command is pending; dropped when the command is
 * acknowledged, after its deletions reached a relay, or when the event has expired anyway.
 */
@Serializable
data class MailboxKey(
    val tracker: String,
    val seq: Int,
    /** id of the 4244 event */
    val id: String,
    /** hex of the event's secret key when it was random (before 0.11); "" = sk_n of [n] */
    val sk: String = "",
    /** the event's NIP-40 expiration (unix s) */
    val exp: Long,
    /** cancelled: the deletion still has to reach a relay */
    val cancelled: Boolean = false,
    /** the event's y tags: a bridge claim (kind 24244) with one of them means a bridge is sending it */
    val ys: List<String> = emptyList(),
    /**
     * The report the entry is sealed for: report n (untimed) or the report whose checks it targets
     * (timed). -1: unknown (keys stored by older app versions), never treated as obsolete.
     */
    val n: Long = -1,
)

class MailboxKeys(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = mutableListOf<MailboxKey>()

    init {
        file?.takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString(ListSerializer(MailboxKey.serializer()), f.readText()) }
                .getOrNull()?.let { stored ->
                    list.addAll(stored.map { it.copy(sk = app.libretag.keys.Secrets.unwrap(it.sk)) })
                    // 0.8.0: keys stored in plain form are rewritten wrapped
                    if (stored.any { !app.libretag.keys.Secrets.isWrapped(it.sk) } && app.libretag.keys.Secrets.box != null) save()
                }
        }
    }

    @Synchronized
    fun add(k: MailboxKey, nowS: Long) {
        list.removeAll { it.exp <= nowS }
        list.add(k)
        save()
    }

    /** Mark every key of command (tracker, seq) as cancelled; returns them. */
    @Synchronized
    fun cancel(tracker: String, seq: Int): List<MailboxKey> {
        var changed = false
        for (i in list.indices) {
            val k = list[i]
            if (k.tracker == tracker && k.seq == seq && !k.cancelled) { list[i] = k.copy(cancelled = true); changed = true }
        }
        if (changed) save()
        return list.filter { it.tracker == tracker && it.seq == seq }
    }

    /**
     * Report [lastN] came in while command (tracker, seq) still waits: mark the entries that can no
     * longer reach the tag as cancelled and return them, so their deletions go out. Those are the
     * entries sealed for an older report: an untimed one waits for a report that is past (a reboot
     * or a long quiet stretch makes the counter jump), and a timed one targets the checks after an
     * older report, while the tag listens only at the checks after its newest one. A bridge would
     * still flood them at their time. The entries for [lastN] itself stay: a bridge may be sending
     * one in this report's window right now, and a deletion arriving during its claim would stop it.
     */
    @Synchronized
    fun obsolete(tracker: String, seq: Int, lastN: Long): List<MailboxKey> {
        val out = mutableListOf<MailboxKey>()
        for (i in list.indices) {
            val k = list[i]
            if (k.tracker == tracker && k.seq == seq && !k.cancelled && k.n in 0 until lastN) {
                list[i] = k.copy(cancelled = true)
                out += list[i]
            }
        }
        if (out.isNotEmpty()) save()
        return out
    }

    /** Cancelled keys whose deletion has not reached a relay yet and whose event has not expired. */
    @Synchronized
    fun toDelete(nowS: Long): List<MailboxKey> = list.filter { it.cancelled && it.exp > nowS }

    /** Forget the keys of command (tracker, seq): acknowledged, or its deletions are out. */
    @Synchronized
    fun remove(tracker: String, seq: Int, ids: Set<String>? = null) {
        if (list.removeAll { it.tracker == tracker && it.seq == seq && (ids == null || it.id in ids) }) save()
    }

    @Synchronized
    fun prune(nowS: Long) {
        if (list.removeAll { it.exp <= nowS }) save()
    }

    @Synchronized
    fun all(): List<MailboxKey> = list.toList()

    /** y tags of the events of commands still pending (not cancelled), for the claim subscription */
    @Synchronized
    fun pendingYs(nowS: Long): Set<String> = list.filter { !it.cancelled && it.exp > nowS }.flatMap { it.ys }.toSet()

    /** The pending (not cancelled) command a bridge claim for tag [y] belongs to. */
    @Synchronized
    fun commandFor(y: String): Pair<String, Int>? = list.firstOrNull { !it.cancelled && y in it.ys }?.let { it.tracker to it.seq }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(MailboxKey.serializer()), list.map { it.copy(sk = app.libretag.keys.Secrets.wrap(it.sk)) }))
        tmp.renameTo(f)
    }

    companion object {
        const val KIND_DELETION = 5
        const val KIND_CLAIM = 24244

        /**
         * NIP-09 deletion of one mailbox event, signed by the event's own key: tags
         * ["e", id], ["k", "4244"] and the event's own ["expiration"], so relays drop the deletion
         * together with the event it removes (strfry keeps kind 5 otherwise).
         */
        fun deletion(k: MailboxKey, nowS: Long, commandKind: Int = 4244, derived: ByteArray? = null): NostrEvent? {
            val sk = (k.sk.takeIf { it.isNotEmpty() }?.unhex() ?: derived)?.takeIf { it.size == 32 } ?: return null
            val tags = listOf(listOf("e", k.id), listOf("k", commandKind.toString()), listOf("expiration", k.exp.toString()))
            return NostrEvent.sign(sk, nowS, KIND_DELETION, tags, "")
        }
    }
}

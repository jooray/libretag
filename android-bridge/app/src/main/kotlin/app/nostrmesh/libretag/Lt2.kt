package app.nostrmesh.libretag

import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.isLowerHex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * What a bridge needs of LibreTag LT2 (PROTOCOL.md section 4): nothing secret. Reports and commands
 * are opaque bytes found by their 4-byte tag `x` = `P[0:4]`; the bridge never holds a tracker key.
 */
object Lt2 {
    const val REPORT_LEN = 45
    const val COMMAND_LEN = 29
    /** BLE packet (PROTOCOL.md 2.3, copies j = 5..8): published as kind 4243 like a report. */
    const val BLE_LEN = 141
    const val TAG_LEN = 4

    const val KIND_REPORT = 4243
    const val KIND_COMMAND = 4244
    const val KIND_CLAIM = 24244

    const val REPORT_TTL_S = 14L * 86400
    const val COMMAND_MAX_TTL_MS = 48 * 3600_000L
    /** NIP-09 deletion; with `#k` 4244 it cancels a mailbox entry. */
    const val KIND_DELETION = 5
    const val MAX_DELETE_IDS = 64

    /** Report sealing v2 (PROTOCOL.md 2): the 3 B commitment c to the mailbox key pk_n sits at P[4:7]. */
    const val COMMIT_OFF = 4
    const val COMMIT_LEN = 3

    /**
     * Does the event key [pubkeyHex] fit the commitment of report [p] (PROTOCOL.md 4.2)?
     * c = SHA-256("LT2-mbc" || pk || P[0:4] || P[7:])[0:3] == P[4:7]. One hash per stored key.
     */
    fun commitMatches(pubkeyHex: String, p: ByteArray): Boolean {
        if (p.size != REPORT_LEN) return false
        val pk = pubkeyHex.unhex()?.takeIf { it.size == 32 } ?: return false
        val h = sha256("LT2-mbc".toByteArray() + pk + p.copyOfRange(0, TAG_LEN) + p.copyOfRange(COMMIT_OFF + COMMIT_LEN, p.size))
        return h.copyOf(COMMIT_LEN).contentEquals(p.copyOfRange(COMMIT_OFF, COMMIT_OFF + COMMIT_LEN))
    }

    /** Datagram lengths on #libretag that go up as kind 4243. */
    fun isUplinkPacket(size: Int) = size == REPORT_LEN || size == BLE_LEN

    fun tag(p: ByteArray): String = p.copyOf(TAG_LEN).hex()

    /**
     * Variant B report event (also a BLE packet): fresh random key per event, tag `y`, expiration [REPORT_TTL_S].
     * [nowS] is when the radio heard it. When only a range is known, [nowS] is its end (the latest
     * possible time, so apps that query with `since` still see the event) and [approxFromS] its start,
     * sent as `["approx", "<earliest>"]` (PROTOCOL.md 4).
     */
    fun reportEvent(p: ByteArray, nowS: Long, sk: ByteArray, approxFromS: Long? = null): NostrEvent = NostrEvent.sign(
        sk, nowS, KIND_REPORT,
        listOf(listOf("y", tag(p)), listOf("expiration", (nowS + REPORT_TTL_S).toString())) +
            (approxFromS?.let { listOf(listOf("approx", minOf(it, nowS).toString())) } ?: emptyList()),
        Base64.getEncoder().encodeToString(p),
    )

    /**
     * When a kind 4243 was heard: (earliest, latest) unix s, latest null for an exact report. With
     * `["approx", v]` the time lies between v and created_at; a missing or malformed v gives an empty
     * range at created_at that is still approximate. Order-insensitive (bridge 0.3.0 had it reversed).
     */
    fun reportTime(ev: NostrEvent): Pair<Long, Long?> {
        val t = ev.tags.firstOrNull { it.isNotEmpty() && it[0] == "approx" } ?: return ev.createdAt to null
        val v = t.getOrNull(1)?.takeIf { it.length in 1..12 && it.all { c -> c in '0'..'9' } }?.toLong() ?: ev.createdAt
        return minOf(v, ev.createdAt) to maxOf(v, ev.createdAt)
    }

    /**
     * Ephemeral "I take x, heard it over h hops" (bridge coordination). An untimed claim of a bridge
     * with a key carries [proof] = SHA-256(P || pubkey) (PROTOCOL.md 4.1, [Market.claimProof]).
     */
    fun claimEvent(y: String, hops: Int, nowS: Long, sk: ByteArray, proof: String? = null): NostrEvent =
        NostrEvent.sign(sk, nowS, KIND_CLAIM,
            listOf(listOf("y", y), listOf("hops", hops.toString())) + listOfNotNull(proof?.let { listOf("proof", it) }), "")

    data class ParsedClaim(val y: String, val hops: Int, val proof: String?)

    /** y, hops and proof of a claim event, or null. */
    fun parseClaim(ev: NostrEvent): ParsedClaim? {
        if (ev.kind != KIND_CLAIM) return null
        val y = ev.tags.firstOrNull { it.size >= 2 && it[0] == "y" }?.get(1)?.takeIf { isLowerHex(it, TAG_LEN) } ?: return null
        val h = ev.tags.firstOrNull { it.size >= 2 && it[0] == "hops" }?.get(1)?.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val proof = ev.tags.firstOrNull { it.size >= 2 && it[0] == "proof" }?.get(1)?.takeIf { isLowerHex(it, 32) }
        return ParsedClaim(y, h, proof)
    }

    /** (y, content) of a report event seen on a relay, or null. */
    fun parseReport(ev: NostrEvent): Pair<String, String>? {
        if (ev.kind != KIND_REPORT) return null
        val y = ev.tags.firstOrNull { it.size >= 2 && it[0] == "y" }?.get(1)?.takeIf { isLowerHex(it, TAG_LEN) } ?: return null
        return y to ev.content
    }
}

/**
 * Bridge election (PROTOCOL.md 4, 4.1): the lowest (index in the entry's p, hops, SHA-256(pubkey || x))
 * wins. [wins] gets only the claims that count (the caller filters them); [index] is 0 for everyone
 * on an open entry and len(p) for a bridge the entry does not name.
 */
object Election {
    data class Claim(val pubkey: String, val hops: Int, val proof: String? = null)

    private data class Rank(val index: Int, val hops: Int, val hash: String)

    private fun rank(c: Claim, y: String, index: (String) -> Int) =
        Rank(index(c.pubkey), c.hops, sha256((c.pubkey.unhex() ?: ByteArray(32)) + (y.unhex() ?: ByteArray(4))).hex())

    private val order = compareBy<Rank>({ it.index }, { it.hops }, { it.hash })

    fun wins(mine: Claim, others: Collection<Claim>, y: String, index: (String) -> Int = { 0 }): Boolean {
        val me = rank(mine, y, index)
        return others.filter { it.pubkey != mine.pubkey }.none { order.compare(rank(it, y, index), me) < 0 }
    }
}

/** Claims heard from other bridges, per y, for a couple of minutes. */
class Claims(private val ttlMs: Long = 120_000, private val cap: Int = 5_000) {
    private val byY = LinkedHashMap<String, MutableList<Pair<Long, Election.Claim>>>()

    @Synchronized
    fun add(y: String, claim: Election.Claim, now: Long) {
        prune(now)
        val l = byY.getOrPut(y) { mutableListOf() }
        if (l.none { it.second.pubkey == claim.pubkey }) l.add(now to claim)
        while (byY.size > cap) byY.remove(byY.keys.first())
    }

    @Synchronized
    fun of(y: String, now: Long): List<Election.Claim> {
        prune(now)
        return byY[y].orEmpty().map { it.second }
    }

    private fun prune(now: Long) {
        val it = byY.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.removeAll { now - it.first > ttlMs }
            if (e.value.isEmpty()) it.remove()
        }
    }
}

/**
 * Uplink dedup (SPEC 6.2 "B with dedup in the gateway"): which relays already carry a report event
 * with this y **and** this content. Content too, or a forged event under a stolen tag would
 * silence honest bridges.
 */
class UplinkDedup(private val ttlMs: Long = 3600_000L, private val cap: Int = 50_000) {
    private class Seen(val firstMs: Long, val relays: MutableSet<String> = mutableSetOf())
    private val seen = LinkedHashMap<String, Seen>()

    private fun key(y: String, content: String) = "$y:$content"

    @Synchronized
    fun saw(y: String, content: String, relay: String, now: Long) {
        prune(now)
        seen.getOrPut(key(y, content)) { Seen(now) }.relays.add(relay)
        while (seen.size > cap) seen.remove(seen.keys.first())
    }

    @Synchronized
    fun relays(y: String, content: String, now: Long): Int {
        prune(now)
        return seen[key(y, content)]?.relays?.size ?: 0
    }

    private fun prune(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.firstMs > ttlMs) it.remove() else break
        }
    }
}

@Serializable
data class StoredCommand(
    val id: String,
    val ys: List<String>,
    val flood: Boolean,
    val payload: String,
    val knownAtMs: Long,
    val expiresAtMs: Long,
    val createdAt: Long,
    val at: Long? = null,
    val via: String? = null,
    val pubkey: String = "",
    /** Unused since the signed mailbox (no proof of work); read from older state files. */
    val pow: Int = 0,
    val relay: String? = null,
    val group: String? = null,
    /** PROTOCOL.md 4.1: format (always 2 now; 1 in older files, dropped), binding route, named bridges, network. */
    val fmt: Int = 1,
    val route: String = "flood",
    val p: List<String> = emptyList(),
    val r: String? = null,
)

@Serializable
data class StoredSent(val pubkey: String, val atMs: Long)

/** An anchor (PROTOCOL.md 4.2): key [pubkey] named report tag [y] first at [knownAtMs]. */
@Serializable
data class StoredAnchor(val y: String, val pubkey: String, val id: String, val knownAtMs: Long, val expiresAtMs: Long)

/** Key [pubkey] fitted the commitment of report [x] at [atMs]. */
@Serializable
data class StoredLock(val x: String, val pubkey: String, val atMs: Long)

@Serializable
data class StoredTimedTx(val via: String, val atMs: Long)

@Serializable
data class StoredMailbox(
    val commands: List<StoredCommand> = emptyList(),
    val done: Map<String, Long> = emptyMap(),
    /** "event id/deletion pubkey" -> when the NIP-09 deletion arrived. */
    val cancelled: Map<String, Long> = emptyMap(),
    /** Transmitted entries (a later deletion is too late). */
    val sent: Map<String, StoredSent> = emptyMap(),
    /** SHA-256 (hex) of every Pc transmitted or heard on air -> when: an identical Pc goes once. */
    val sentPayloads: Map<String, Long> = emptyMap(),
    /** Own timed transmissions (per-via gap, hourly cap). */
    val timedTx: List<StoredTimedTx> = emptyList(),
    /** Command groups whose tracker answered -> when. */
    val doneGroups: Map<String, Long> = emptyMap(),
    /** Signed mailbox (PROTOCOL.md 4.2): anchors and locked keys. */
    val anchors: List<StoredAnchor> = emptyList(),
    val locks: List<StoredLock> = emptyList(),
)

/**
 * Pending LT2 commands by report tag (kind 4244). A command is sent only if the bridge knew it
 * before it heard the report (or as a late entry), only from a key the report locked (signed
 * mailbox, PROTOCOL.md 4.2: [lockCandidates], [lock], [locked]), once per report and key, and never
 * after its expiration (at most 48 h). Anchors (content "") are kept apart ([addAnchor]).
 *
 * Timed entries (0.7 checks, [Command.at] set) never answer a report: they go out at `at` along the
 * path stored for [Command.via] ([dueTimed]). A NIP-09 deletion signed by the event's own key
 * cancels an entry that has not gone out yet ([cancel]); the deletion is remembered, so the event
 * arriving later is not added again.
 *
 * Limits (PROTOCOL.md 4, "Limits", same as host/src/nostrmesh/ledger.py): at most [add]'s
 * `perViaMax` timed entries per `via` (earlier seen stay), one source relay holds at most
 * `relayShare` of [cap] tags, and a full mailbox evicts the oldest inserted. An identical Pc goes out once ([payloadSent]); groups whose tracker answered are closed
 * ([closeGroup]).
 */
class Mailbox(private val cap: Int = DEFAULT_CAP) {
    class Command(
        val id: String,
        val ys: List<String>,
        val flood: Boolean,
        val payload: ByteArray,
        val knownAtMs: Long,
        val expiresAtMs: Long,
        val createdAt: Long,
        /** Timed entry: transmit at this unix second. */
        val at: Long? = null,
        /** Timed entry: x of the report whose path to use. */
        val via: String? = null,
        /** Author of the 4244 event: the mailbox key of its report; a deletion must come from the same key. */
        val pubkey: String = "",
        /** The relay it came from first (per-relay quota). */
        val relay: String? = null,
        /** ["g"]: all entries of one command. */
        val group: String? = null,
        /** Always 2 (PROTOCOL.md 4.1); format 1 is gone. */
        val fmt: Int = 2,
        /** The most the entry asks for ("near", "direct", "flood"). */
        val route: String = "flood",
        /** Bridges that may send it, in order of preference; empty = open. */
        val p: List<String> = emptyList(),
        /** The tag's network. */
        val r: String? = null,
    ) {
        val timed get() = at != null
        /** Any bridge may send it (no p). */
        val open get() = p.isEmpty()
        /** An anchor (PROTOCOL.md 4.2): content "", names the next reports' tags, carries no command. */
        val anchor get() = payload.isEmpty()

        fun withYs(ys: List<String>) = Command(id, ys, flood, payload, knownAtMs, expiresAtMs, createdAt, at, via, pubkey,
            relay, group, fmt, route, p, r)

        /** The `done` key of tag [y]: untimed entries are answered once per report and key, timed ones per tag. */
        fun doneKey(y: String) = if (timed) y else "$y:$pubkey"
    }

    enum class Cancel { CANCELLED, SENT, OTHER_KEY, UNKNOWN }

    private val byId = LinkedHashMap<String, Command>()
    private val byY = HashMap<String, MutableList<Command>>()
    /** y -> when it was handled (sent, lost an election or heard on air): never twice. */
    private val done = LinkedHashMap<String, Long>()
    /** Taken for delivery (claim or `at` pending), not transmitted yet: a deletion still stops it. */
    private val inFlight = LinkedHashMap<String, Command>()
    private val sent = LinkedHashMap<String, StoredSent>()
    private val cancelled = LinkedHashMap<String, Long>()
    private val sentPayloads = LinkedHashMap<String, Long>()
    private val timedTx = ArrayDeque<StoredTimedTx>()
    private val doneGroups = LinkedHashMap<String, Long>()
    /** y -> (pubkey -> anchor), first seen first. */
    private val anchors = LinkedHashMap<String, LinkedHashMap<String, StoredAnchor>>()
    /** x -> (pubkey -> when locked). */
    private val locks = LinkedHashMap<String, LinkedHashMap<String, Long>>()
    private var tags = 0

    val size: Int @Synchronized get() = byId.size
    val tagCount: Int @Synchronized get() = tags

    /** True when the entry is new and stays after the limits (see the class comment). */
    @Synchronized
    fun add(c: Command, now: Long, perViaMax: Int? = null, relayShare: Double? = null): Boolean {
        prune(now)
        if (byId.containsKey(c.id) || inFlight.containsKey(c.id) || sent.containsKey(c.id) || c.expiresAtMs <= now) return false
        if (isCancelled(c.id, c.pubkey)) return false
        if (c.ys.all { c.doneKey(it) in done }) return false
        byId[c.id] = c
        c.ys.forEach { byY.getOrPut(it) { mutableListOf() }.add(c) }
        tags += c.ys.size
        if (c.timed && perViaMax != null && perViaMax > 0) {
            byId.values.filter { it.timed && it.via == c.via }
                .sortedWith(compareBy<Command>({ it.knownAtMs }, { it.id }))
                .drop(perViaMax).forEach { remove(it) }
        }
        if (c.relay != null && relayShare != null && relayShare > 0) evict((cap * relayShare).toInt()) { it.relay == c.relay }
        evict(cap) { true }
        return byId.containsKey(c.id)
    }

    /** Drops whole entries matching [which]: oldest inserted first, until at most [limit] tags remain. */
    private fun evict(limit: Int, which: (Command) -> Boolean) {
        var count = byId.values.filter(which).sumOf { it.ys.size }
        if (count <= limit) return
        for (c in byId.values.filter(which).sortedBy { it.knownAtMs }) {
            if (count <= limit) break
            remove(c)
            count -= c.ys.size
        }
    }

    @Synchronized
    fun relayTags(relay: String) = byId.values.filter { it.relay == relay }.sumOf { it.ys.size }

    // ------------------------------------------------------------------ signed mailbox (PROTOCOL.md 4.2)

    /**
     * Remembers that key [pubkey] named tag [y] in an anchor. The first time counts; a later anchor of the
     * same key only extends the expiration. At most [cap] anchors in all, the oldest go first. True if new.
     */
    @Synchronized
    fun addAnchor(y: String, pubkey: String, id: String, knownAtMs: Long, expiresAtMs: Long): Boolean {
        val m = anchors.getOrPut(y) { LinkedHashMap() }
        val old = m[pubkey]
        if (old != null) {
            if (expiresAtMs > old.expiresAtMs) m[pubkey] = old.copy(expiresAtMs = expiresAtMs)
            return false
        }
        m[pubkey] = StoredAnchor(y, pubkey, id, knownAtMs, expiresAtMs)
        var count = anchors.values.sumOf { it.size }
        while (count > cap) {
            val oldest = anchors.values.flatMap { it.values }.minByOrNull { it.knownAtMs } ?: break
            anchors[oldest.y]?.remove(oldest.pubkey)
            if (anchors[oldest.y]?.isEmpty() == true) anchors.remove(oldest.y)
            count--
        }
        return true
    }

    @Synchronized
    fun anchorCount() = anchors.values.sumOf { it.size }

    /** Distinct keys that named report tag [y] (anchors and untimed entries). */
    @Synchronized
    fun keysOfY(y: String): Set<String> =
        anchors[y].orEmpty().keys + byY[y].orEmpty().filter { !it.timed }.map { it.pubkey }

    /** Keys that named tag [x] at [beforeMs] or earlier (an anchor or an untimed entry): they may lock. */
    @Synchronized
    fun lockCandidates(x: String, beforeMs: Long, now: Long): Set<String> =
        anchors[x].orEmpty().values.filter { it.knownAtMs <= beforeMs && it.expiresAtMs > now }.map { it.pubkey }.toSet() +
            (byY[x].orEmpty() + inFlight.values.filter { x in it.ys }).filter { !it.timed && it.knownAtMs <= beforeMs }
                .map { it.pubkey }

    /** True when [pubkey] is newly locked for report [x]. */
    @Synchronized
    fun lock(x: String, pubkey: String, now: Long): Boolean {
        val m = locks.getOrPut(x) { LinkedHashMap() }
        if (m.containsKey(pubkey)) return false
        m[pubkey] = now
        while (locks.size > cap) locks.remove(locks.keys.first())
        return true
    }

    @Synchronized
    fun locked(x: String): Set<String> = locks[x]?.keys?.toSet().orEmpty()

    /** After report [x] locked its keys: unsent entries for it from any key not in [keep] can never go out. */
    @Synchronized
    fun dropUnlocked(x: String, keep: Set<String>): Int {
        val drop = byId.values.filter { c -> ((!c.timed && x in c.ys) || c.via == x) && c.pubkey !in keep }
        drop.forEach { remove(it) }
        return drop.size
    }

    // ------------------------------------------------------------------ A6, A3/A4, groups

    @Synchronized
    fun payloadSent(payload: ByteArray) = sentPayloads.containsKey(sha256(payload).hex())

    @Synchronized
    fun markPayloadSent(payload: ByteArray, now: Long) {
        sentPayloads[sha256(payload).hex()] = now
        while (sentPayloads.size > cap) sentPayloads.remove(sentPayloads.keys.first())
    }

    @Synchronized
    fun addTimedTx(via: String, now: Long) {
        timedTx.addLast(StoredTimedTx(via, now))
        while (timedTx.size > 1000) timedTx.removeFirst()
    }

    @Synchronized
    fun lastTimedTx(via: String): Long? = timedTx.filter { it.via == via }.maxOfOrNull { it.atMs }

    @Synchronized
    fun timedTxSince(sinceMs: Long) = timedTx.count { it.atMs >= sinceMs }

    @Synchronized
    fun groupDone(g: String) = doneGroups.containsKey(g)

    /** Marks group [g] answered and drops its timed entries that have not gone out (also those waiting for `at`). */
    @Synchronized
    fun closeGroup(g: String, now: Long): Int {
        doneGroups[g] = now
        while (doneGroups.size > cap) doneGroups.remove(doneGroups.keys.first())
        val drop = (byId.values + inFlight.values).filter { it.timed && it.group == g }.distinctBy { it.id }
        drop.forEach { remove(it); inFlight.remove(it.id) }
        return drop.size
    }

    /** Groups with an untimed entry for tag [y] (a report with this x is the tracker's own). */
    @Synchronized
    fun groupsOfY(y: String): List<String> = byY[y].orEmpty().filter { !it.timed }.mapNotNull { it.group }.distinct()

    /** Timed entries of [g] still waiting (in the mailbox or in flight). */
    @Synchronized
    fun groupTimed(g: String): List<Command> = (byId.values + inFlight.values).filter { it.timed && it.group == g }.distinctBy { it.id }

    @Synchronized
    fun groupHasUntimedY(g: String, y: String) = byId.values.any { !it.timed && it.group == g && y in it.ys }

    /**
     * Per key in [keys] (the keys report [y] locked), the command for tag [y] heard at [heardAtMs], known
     * before then and one that [allowed] (its route) lets this bridge send; of several the one seen first.
     * A key already answered for [y] gets nothing. Timed entries never match.
     */
    @Synchronized
    fun match(y: String, heardAtMs: Long, now: Long, keys: Set<String>, allowed: (Command) -> Boolean = { true }): List<Command> =
        byY[y].orEmpty()
            .filter {
                !it.timed && it.pubkey in keys && "$y:${it.pubkey}" !in done &&
                    it.knownAtMs < heardAtMs && it.expiresAtMs > now && allowed(it)
            }
            .sortedWith(compareBy<Command>({ it.knownAtMs }, { it.id }))
            .distinctBy { it.pubkey }

    /** An untimed entry waits under tag [y]. */
    @Synchronized
    fun hasUntimed(y: String) = byY[y].orEmpty().any { !it.timed }

    /** Drops [c] without closing its tags (an entry this bridge may not send: the y stays for a nearer bridge). */
    @Synchronized
    fun discard(c: Command) {
        remove(c)
    }

    /** Unexpired timed entries with `at` <= [untilMs] none of whose tags were answered, earliest first. */
    @Synchronized
    fun dueTimed(untilMs: Long, now: Long): List<Command> =
        byId.values.filter { c ->
            c.at != null && c.at * 1000 <= untilMs && c.expiresAtMs > now && c.ys.none { c.doneKey(it) in done }
        }.sortedWith(compareBy<Command>({ it.at }, { -it.createdAt }, { it.id }))

    /**
     * Takes [c] out (it goes out at most once) and closes its tags (an untimed entry for its key only); it
     * stays in flight until [markSent] or [release].
     */
    @Synchronized
    fun take(c: Command, now: Long) {
        c.ys.forEach { done[c.doneKey(it)] = now }
        // other commands under the same tags from the same key are for the same report: one downlink per x and key
        c.ys.flatMap { byY[it].orEmpty() }.distinct().filter { it.timed || it.pubkey == c.pubkey }.forEach { remove(it) }
        remove(c)
        inFlight[c.id] = c
        while (inFlight.size > 1024) inFlight.remove(inFlight.keys.first())
    }

    /** Still waiting to be transmitted (not cancelled, not sent, not given up). */
    @Synchronized
    fun pending(id: String) = inFlight.containsKey(id) || byId.containsKey(id)

    @Synchronized
    fun wasSent(id: String) = sent.containsKey(id)

    @Synchronized
    fun markSent(c: Command, now: Long) {
        inFlight.remove(c.id)
        remove(c)
        sent[c.id] = StoredSent(c.pubkey, now)
        while (sent.size > cap) sent.remove(sent.keys.first())
    }

    /** Delivery given up (lost the election, missed `at`, radio refused): the tags stay closed. */
    @Synchronized
    fun release(c: Command) {
        inFlight.remove(c.id)
    }

    /** Someone else's radio sent this command payload: drop it here. */
    @Synchronized
    fun heardOnAir(payload: ByteArray, now: Long): Command? {
        markPayloadSent(payload, now)
        val c = (byId.values + inFlight.values).firstOrNull { it.payload.contentEquals(payload) } ?: return null
        take(c, now)
        markSent(c, now)
        return c
    }

    @Synchronized
    fun isCancelled(id: String, pubkey: String) = cancelled.containsKey("$id/$pubkey")

    /**
     * Applies a NIP-09 deletion of event [id] signed by [pubkey] and remembers it (also when the
     * event has not arrived yet: [Cancel.UNKNOWN]).
     */
    @Synchronized
    fun cancel(id: String, pubkey: String, now: Long): Cancel {
        val c = byId[id] ?: inFlight[id]
        val author = c?.pubkey ?: sent[id]?.pubkey
        if (author != null && author != pubkey) return Cancel.OTHER_KEY
        cancelled["$id/$pubkey"] = now
        while (cancelled.size > cap) cancelled.remove(cancelled.keys.first())
        return when {
            sent.containsKey(id) -> Cancel.SENT
            c == null -> Cancel.UNKNOWN
            else -> {
                remove(c)
                inFlight.remove(id)
                Cancel.CANCELLED
            }
        }
    }

    private fun remove(c: Command) {
        if (byId.remove(c.id) == null) return
        tags -= c.ys.size
        c.ys.forEach { y -> byY[y]?.let { it.remove(c); if (it.isEmpty()) byY.remove(y) } }
    }

    @Synchronized
    fun prune(now: Long) {
        byId.values.filter { it.expiresAtMs <= now }.forEach { remove(it) }
        inFlight.values.removeAll { it.expiresAtMs <= now }
        val it = done.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > Lt2.COMMAND_MAX_TTL_MS) it.remove()
        sent.entries.removeAll { now - it.value.atMs > Lt2.COMMAND_MAX_TTL_MS }
        cancelled.entries.removeAll { now - it.value > KEEP_CANCELLED_MS }
        sentPayloads.entries.removeAll { now - it.value > KEEP_CANCELLED_MS }
        doneGroups.entries.removeAll { now - it.value > KEEP_CANCELLED_MS }
        while (timedTx.isNotEmpty() && now - timedTx.first().atMs > 2 * 3600_000L) timedTx.removeFirst()
        anchors.values.forEach { m -> m.values.removeAll { it.expiresAtMs <= now } }
        anchors.values.removeAll { it.isEmpty() }
        locks.values.forEach { m -> m.values.removeAll { now - it > LOCK_TTL_MS } }
        locks.values.removeAll { it.isEmpty() }
    }

    @Synchronized
    fun export(): StoredMailbox = StoredMailbox(
        (byId.values + inFlight.values).map {
            StoredCommand(it.id, it.ys, it.flood, Base64.getEncoder().encodeToString(it.payload), it.knownAtMs,
                it.expiresAtMs, it.createdAt, it.at, it.via, it.pubkey, 0, it.relay, it.group, it.fmt, it.route, it.p, it.r)
        },
        done.toMap(),
        cancelled.toMap(),
        sent.toMap(),
        sentPayloads.toMap(),
        timedTx.toList(),
        doneGroups.toMap(),
        anchors.values.flatMap { it.values },
        locks.flatMap { (x, m) -> m.map { (pk, t) -> StoredLock(x, pk, t) } },
    )

    @Synchronized
    fun restore(s: StoredMailbox, now: Long) {
        byId.clear(); byY.clear(); done.clear(); inFlight.clear(); sent.clear(); cancelled.clear(); tags = 0
        sentPayloads.clear(); timedTx.clear(); doneGroups.clear(); anchors.clear(); locks.clear()
        s.anchors.filter { it.expiresAtMs > now }.sortedBy { it.knownAtMs }
            .forEach { addAnchor(it.y, it.pubkey, it.id, it.knownAtMs, it.expiresAtMs) }
        s.locks.filter { now - it.atMs <= LOCK_TTL_MS }.sortedBy { it.atMs }.forEach { lock(it.x, it.pubkey, it.atMs) }
        s.sentPayloads.filter { now - it.value <= KEEP_CANCELLED_MS }.forEach { (k, t) -> sentPayloads[k] = t }
        s.doneGroups.filter { now - it.value <= KEEP_CANCELLED_MS }.forEach { (k, t) -> doneGroups[k] = t }
        s.timedTx.filter { now - it.atMs <= 2 * 3600_000L }.sortedBy { it.atMs }.forEach { timedTx.addLast(it) }
        s.cancelled.filter { now - it.value <= KEEP_CANCELLED_MS }.forEach { (k, t) -> cancelled[k] = t }
        s.sent.filter { now - it.value.atMs <= Lt2.COMMAND_MAX_TTL_MS }.forEach { (k, v) -> sent[k] = v }
        s.done.filter { now - it.value <= Lt2.COMMAND_MAX_TTL_MS }.forEach { (y, t) -> done[y] = t }
        for (c in s.commands) {
            val p = runCatching { Base64.getDecoder().decode(c.payload) }.getOrNull() ?: continue
            // an entry in flight when the bridge stopped has its tags closed: add() drops it (as the Python ledger does)
            if (c.fmt != 2) continue // format 1 (before the signed mailbox) never goes out
            add(Command(c.id, c.ys, c.flood, p, c.knownAtMs, c.expiresAtMs, c.createdAt, c.at, c.via, c.pubkey, c.relay, c.group,
                c.fmt, c.route, c.p, c.r), now)
        }
    }

    companion object {
        /** Tags in all (PROTOCOL.md 4.1 "Mailbox size"; was 10,000). */
        const val DEFAULT_CAP = 200_000
        const val MAX_TAGS_PER_EVENT = 16
        /** Deletions are remembered as long as the events they cancel can still arrive. */
        const val KEEP_CANCELLED_MS = 30L * 86400_000
        /** Locks are kept two days (the longest timed entry is 25 h after its report). */
        const val LOCK_TTL_MS = 2L * 86400_000

        const val GROUP_BYTES = 8

        /**
         * A kind 4244 event as a command or an anchor (empty content, [Command.anchor]), or null (the
         * signature is checked by the caller). An entry with `at` needs a `via` tag; it is refused when
         * [timed] is off or `at` is more than [maxLateMs] past; an anchor never has `at`. A `g` tag must be
         * 16 lowercase hex. created_at at most [futureMaxMs] ahead. No `["v", "2"]` (format 1) or
         * malformed targeting ([Market.targeting]) makes the event invalid.
         */
        fun parse(
            ev: NostrEvent, nowMs: Long, timed: Boolean = true, maxLateMs: Long = 60_000,
            futureMaxMs: Long = 60_000, relay: String? = null,
        ): Command? {
            if (ev.kind != Lt2.KIND_COMMAND) return null
            val ys = ev.tags.filter { it.size >= 2 && it[0] == "y" && isLowerHex(it[1], Lt2.TAG_LEN) }.map { it[1] }.distinct()
            if (ys.isEmpty() || ys.size > MAX_TAGS_PER_EVENT) return null
            val group = ev.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)
            if (group != null && !isLowerHex(group, GROUP_BYTES)) return null
            val createdMs = ev.createdAt * 1000
            if (createdMs > nowMs + futureMaxMs) return null
            val maxExp = minOf(createdMs, nowMs) + Lt2.COMMAND_MAX_TTL_MS
            val exp = ev.tags.firstOrNull { it.size >= 2 && it[0] == "expiration" }?.get(1)?.toLongOrNull()?.times(1000)
            val expiresAt = minOf(exp ?: maxExp, maxExp)
            if (expiresAt <= nowMs) return null
            if (!isLowerHex(ev.pubkey, 32)) return null
            val payload = try {
                Base64.getDecoder().decode(ev.content)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (payload.size != Lt2.COMMAND_LEN && ev.content.isNotEmpty()) return null
            val t = Market.targeting(ev.tags) ?: return null
            val flood = ev.tags.any { it.size >= 2 && it[0] == "route" && it[1] == "flood" }
            val atS = ev.tags.firstOrNull { it.size >= 2 && it[0] == "at" }?.get(1)
            var at: Long? = null
            var via: String? = null
            if (atS != null) {
                via = ev.tags.firstOrNull { it.size >= 2 && it[0] == "via" }?.get(1)
                if (atS.isEmpty() || !atS.all { it in '0'..'9' } || atS.length > 12) return null
                if (via == null || !isLowerHex(via, Lt2.TAG_LEN)) return null
                if (payload.isEmpty()) return null // an anchor names reports, never a check
                if (!timed) return null
                at = atS.toLong()
                if (at * 1000 < nowMs - maxLateMs) return null
            }
            return Command(ev.id, ys, flood, payload, nowMs, expiresAt, ev.createdAt, at, via, ev.pubkey,
                relay?.takeIf { it.isNotEmpty() }, group, t.fmt, t.route, t.p, t.r)
        }
    }
}

/**
 * How each caught LT2 report reached this radio, by its tag x (only the 4-byte tag, hop count and
 * path bytes), for timed mailbox entries. Of several copies the one with the fewest hops stays.
 */
class ReportPaths(private val ttlMs: Long = 48 * 3600_000L, private val cap: Int = 20_000) {
    @Serializable
    data class Entry(val x: String, val hops: Int? = null, val path: String? = null, val hashSize: Int = 1, val heardAtMs: Long)

    private val byX = LinkedHashMap<String, Entry>()

    val size: Int @Synchronized get() = byX.size

    @Synchronized
    fun put(x: String, hops: Int?, path: ByteArray?, hashSize: Int, heardAtMs: Long) {
        val old = byX[x]
        if (old?.hops != null && (hops == null || hops >= old.hops)) return
        byX.remove(x)
        byX[x] = Entry(x, hops, path?.hex(), hashSize, heardAtMs)
        while (byX.size > cap) byX.remove(byX.keys.first())
    }

    @Synchronized
    fun get(x: String, now: Long): Entry? = byX[x]?.takeIf { now - it.heardAtMs <= ttlMs }

    @Synchronized
    fun prune(now: Long) {
        byX.values.removeAll { now - it.heardAtMs > ttlMs }
    }

    @Synchronized
    fun export(): List<Entry> = byX.values.toList()

    @Synchronized
    fun restore(entries: List<Entry>, now: Long) {
        byX.clear()
        entries.filter { now - it.heardAtMs <= ttlMs }.sortedBy { it.heardAtMs }.forEach { byX[it.x] = it }
    }
}

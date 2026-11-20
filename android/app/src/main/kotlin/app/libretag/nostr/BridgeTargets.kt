package app.libretag.nostr

/**
 * Bridge receipts (kind 4245), announcements (kind 38244) and the targeting of format 2 mailbox
 * entries (PROTOCOL.md 4.1). Pure functions; reference: host/src/nostrmesh/market.py, vectors
 * mailbox-v2.json.
 */

/** A bridge heard report `x` = [y] (hops class "0", "1-3", "4+" or "?") at [heardAt]. */
data class Receipt(val pubkey: String, val y: String, val hops: String, val heardAt: Long, val expires: Long)

/** A bridge's valid, unexpired announcement for one network ([network] "" when it names none). */
data class Announcement(
    val pubkey: String,
    val network: String,
    val open: Boolean,
    val receipts: Boolean,
    val expires: Long,
    /** the relays it reads entries from (["relay", url] tags) */
    val relays: List<String> = emptyList(),
)

/** A receipt for a report the app has not opened: the counter it stands for and the bridge's announcement. */
data class AheadHit(val receipt: Receipt, val n: Long, val announcement: Announcement)

/** Who may send an entry: [p] empty = open. */
data class Targets(val p: List<String>, val r: String?) {
    val open: Boolean get() = p.isEmpty()

    companion object {
        val OPEN = Targets(emptyList(), null)
    }
}

/** What a 4244 event's tags say about who may send it and how (format 2, the only one), as market.targeting. */
data class Targeting(val format: Int, val route: String, val p: List<String>, val r: String?) {
    val open: Boolean get() = p.isEmpty()
}

object BridgeTargets {
    const val RECEIPT_KIND = 4245
    const val ANNOUNCE_KIND = 38244
    const val RECEIPT_TTL_S = 48 * 3600L
    /** bridges named per entry (the protocol allows 4; the app names at most 3) */
    const val MAX_TARGETS = 3
    const val MAX_P = 4
    const val MAX_P_TAGS = 8
    /** reports of the tag whose receipts count as "recent" */
    const val RECENT_REPORTS = 10
    /** the route the app asks for, every command (owner decision 2026-11-18) */
    const val ROUTE = "flood"
    val ROUTES = listOf("near", "direct", "flood")
    private val HOPS_RANK = mapOf("0" to 0, "1-3" to 1, "4+" to 2, "?" to 3)
    private val NETWORK = Regex("^[a-z0-9-]{1,32}$")
    private const val FUTURE_MAX_S = 60L

    fun isNetwork(s: String) = NETWORK.matches(s)

    fun hopsClass(hops: Int?): String = when {
        hops == null -> "?"
        hops == 0 -> "0"
        hops <= 3 -> "1-3"
        else -> "4+"
    }

    /** Hearing proof on an untimed claim: SHA-256(P || pubkey). */
    fun claimProof(p: ByteArray, pubkeyHex: String): String = sha256(p + (pubkeyHex.unhex() ?: ByteArray(0))).hex()

    /** null: malformed (no ["v", "2"]: format 1 is gone; bad p or r, too many p tags, unknown route). */
    fun targeting(tags: List<List<String>>): Targeting? {
        val ts = tags.filter { it.size >= 2 }
        if (ts.none { it[0] == "v" && it[1] == "2" }) return null
        val route = ts.firstOrNull { it[0] == "route" }?.get(1) ?: "flood"
        if (route !in ROUTES) return null
        val ps = ts.filter { it[0] == "p" }.map { it[1] }
        if (ps.size > MAX_P_TAGS || !ps.all { isLowerHex(it, 32) }) return null
        val rs = ts.filter { it[0] == "r" }.map { it[1] }
        if (rs.size > 1 || !rs.all { isNetwork(it) }) return null
        return Targeting(2, route, ps.distinct().take(MAX_P), rs.firstOrNull())
    }

    /** The format 2 tags of an entry: ["v","2"], ["route", flood], a ["p"] per target, ["r"] when known. */
    fun entryTags(t: Targets): List<List<String>> =
        listOf(listOf("v", "2"), listOf("route", ROUTE)) +
            targetTags(t)

    /** ["p"] per target and ["r"] when known: an anchor (PROTOCOL.md 4.2) carries them without a route. */
    fun targetTags(t: Targets): List<List<String>> =
        t.p.take(MAX_P).map { listOf("p", it) } + listOfNotNull(t.r?.let { listOf("r", it) })

    private fun tagMap(ev: NostrEvent): Map<String, List<String>> {
        val m = LinkedHashMap<String, List<String>>()
        for (t in ev.tags) if (t.size >= 2) m.putIfAbsent(t[0], t)
        return m
    }

    /** A valid, unexpired receipt (signature checked when [verify]), or null. */
    fun parseReceipt(ev: NostrEvent, nowS: Long, verify: Boolean = true): Receipt? {
        if (ev.kind != RECEIPT_KIND || !isLowerHex(ev.pubkey, 32)) return null
        if (ev.createdAt > nowS + FUTURE_MAX_S) return null
        val m = tagMap(ev)
        val y = m["y"]?.get(1)?.takeIf { it.isNotEmpty() && it.length % 2 == 0 && it.all { c -> c in "0123456789abcdef" } } ?: return null
        val hops = m["hops"]?.get(1)?.takeIf { it in HOPS_RANK } ?: "?"
        val exp = m["expiration"]?.get(1)?.toLongOrNull() ?: (ev.createdAt + RECEIPT_TTL_S)
        if (exp <= nowS || ev.createdAt + RECEIPT_TTL_S <= nowS) return null
        if (verify && !ev.verify()) return null
        return Receipt(ev.pubkey, y, hops, ev.createdAt, exp)
    }

    /** A valid, unexpired announcement of the LibreTag mailbox (signature checked when [verify]), or null. */
    fun parseAnnouncement(ev: NostrEvent, nowS: Long, verify: Boolean = true): Announcement? {
        if (ev.kind != ANNOUNCE_KIND || !isLowerHex(ev.pubkey, 32)) return null
        val m = tagMap(ev)
        if (m["k"]?.get(1) != "4244") return null
        val d = m["d"]?.get(1) ?: return null
        if (d.isNotEmpty() && !isNetwork(d)) return null
        val exp = m["expiration"]?.get(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return null
        if (exp <= nowS) return null
        if (verify && !ev.verify()) return null
        val relays = ev.tags.filter { it.size >= 2 && it[0] == "relay" && (it[1].startsWith("wss://") || it[1].startsWith("ws://")) }
            .map { it[1] }.distinct().take(8)
        return Announcement(ev.pubkey, d, open = (m["open"]?.get(1) ?: "1") == "1",
            receipts = m["receipts"]?.get(1) == "1", expires = exp, relays = relays)
    }

    /**
     * The tag's `x` values ([yOf] for j = 0 and 1) of its last [count] reports up to [lastN], plus
     * [extraN] (the report a quiet stretch's checks hang on), mapped to their report counter.
     */
    fun recentYs(lastN: Long, extraN: Long?, yOf: (Long, Int) -> String?, count: Int = RECENT_REPORTS): Map<String, Long> {
        val ns = ((maxOf(0L, lastN - count + 1))..lastN).toMutableSet()
        if (extraN != null && extraN >= 0) ns += extraN
        val out = LinkedHashMap<String, Long>()
        for (n in ns.sorted()) for (j in 0..1) yOf(n, j)?.let { out[it] = n }
        return out
    }

    /**
     * Bridges to name in an entry: those with a valid unexpired announcement and a receipt for one
     * of [ys] (y -> report counter), ranked by (reports heard desc, best hops class, most recent),
     * at most [MAX_TARGETS]. r = the one network all of them announce, else none. None heard: open.
     */
    fun choose(receipts: List<Receipt>, announcements: List<Announcement>, ys: Map<String, Long>, nowS: Long): Targets {
        val anns = announcements.filter { it.expires > nowS }.groupBy { it.pubkey }
        val heard = receipts.filter { it.y in ys && it.expires > nowS && it.pubkey in anns }.groupBy { it.pubkey }
        if (heard.isEmpty()) return Targets.OPEN
        val ranked = heard.entries.sortedWith(
            compareByDescending<Map.Entry<String, List<Receipt>>> { e -> e.value.map { ys.getValue(it.y) }.toSet().size }
                .thenBy { e -> e.value.minOf { HOPS_RANK.getValue(it.hops) } }
                .thenByDescending { e -> e.value.maxOf { it.heardAt } }
                .thenBy { it.key },
        ).take(MAX_TARGETS).map { it.key }
        val nets = ranked.map { pk -> anns.getValue(pk).map { it.network }.filter { it.isNotEmpty() }.toSet() }
        val common = nets.reduce { a, b -> a intersect b }
        return Targets(ranked, common.singleOrNull())
    }

    /**
     * The counters a late tag may have sent that the app has not opened (docs/design/tag-diagnosis.md
     * 4.4): lastN+1 … lastN+4, the next two multiples of 16 above lastN (a restart lands on a state
     * checkpoint) and the next multiple of 64 (an epoch skip). Their `x` for j = 0 and 1, at most 14.
     */
    fun nextYs(lastN: Long, yOf: (Long, Int) -> String?): Map<String, Long> {
        if (lastN < 0) return emptyMap()
        val ns = sortedSetOf<Long>()
        for (d in 1..4) ns += lastN + d
        val c16 = (lastN / 16 + 1) * 16
        ns += c16; ns += c16 + 16
        ns += (lastN / 64 + 1) * 64
        val out = LinkedHashMap<String, Long>()
        for (n in ns) for (j in 0..1) yOf(n, j)?.let { out[it] = n }
        return out
    }

    /**
     * The newest receipt for one of [ys] (y -> counter) from a bridge with a valid announcement; a
     * receipt from a key without one counts for nothing (anyone can copy x from the relays).
     */
    fun ahead(receipts: List<Receipt>, announcements: List<Announcement>, ys: Map<String, Long>, nowS: Long): AheadHit? {
        val anns = announcements.filter { it.expires > nowS }.groupBy { it.pubkey }
        return receipts.filter { it.y in ys && it.expires > nowS && it.pubkey in anns }
            .maxWithOrNull(compareBy<Receipt>({ ys.getValue(it.y) }, { it.heardAt }))
            ?.let { AheadHit(it, ys.getValue(it.y), anns.getValue(it.pubkey).first()) }
    }

    /** Different UTC days on which [pubkey] had receipts for [ys]: "Add relay" needs two (owner decision 8). */
    fun receiptDays(receipts: List<Receipt>, pubkey: String, ys: Set<String>): Int =
        receipts.filter { it.pubkey == pubkey && it.y in ys }.map { it.heardAt / 86_400L }.toSet().size

    /** Untimed entries: bridges that heard the tag's recent reports. */
    fun untimed(receipts: List<Receipt>, announcements: List<Announcement>, recent: Map<String, Long>, nowS: Long) =
        choose(receipts, announcements, recent, nowS)

    /** Timed entries: bridges with a receipt for exactly report [via]. */
    fun timed(receipts: List<Receipt>, announcements: List<Announcement>, via: String, nowS: Long) =
        choose(receipts, announcements, mapOf(via to 0L), nowS)
}

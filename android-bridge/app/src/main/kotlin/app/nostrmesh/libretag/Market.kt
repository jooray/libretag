package app.nostrmesh.libretag

import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.isLowerHex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex

/**
 * Bridge identity, announcements, receipts and targeted mailbox entries (PROTOCOL.md 4.1). Port of
 * host/src/nostrmesh/market.py, checked against mailbox-v2.json:
 *
 * - format 2 entries (`["v", "2"]`), the only format: binding route, `["p", bridge]` targeting, `["r", network]`;
 * - the claim proof SHA-256(P || pubkey) that only a bridge which heard report P can make in time;
 * - the receipt hop class and the announcement (kind 38244) tags.
 */
object Market {
    const val RECEIPT_KIND = 4245
    const val ANNOUNCE_KIND = 38244
    const val FORMAT2 = "2"
    val ROUTES = listOf("near", "direct", "flood", "both")
    /** Bridges named per entry that count. */
    const val MAX_P = 4
    /** More p tags than this: the entry is invalid. */
    const val MAX_P_TAGS = 8
    const val RECEIPT_TTL_S = 48L * 3600
    const val ANNOUNCE_TTL_S = 7L * 86400
    /** `direct_max_hops` (SPEC 3.2): a path of at most two repeaters; 0 turns direct routing off. */
    const val DIRECT_MAX_HOPS = QuietMesh.PATH_MAX_HOPS

    private val NETWORK = Regex("^[a-z0-9-]{1,32}$")

    fun isNetwork(s: String?) = s != null && NETWORK.matches(s)

    /** What a 4244 event's tags say about who may send it and how (format 1 or 2). */
    data class Targeting(val fmt: Int, val route: String, val p: List<String>, val r: String?) {
        val open get() = p.isEmpty()
        fun pIndex(pubkey: String?): Int? = p.indexOf(pubkey).takeIf { it >= 0 }
    }

    /**
     * null: malformed (no `["v", "2"]`, bad p or r, too many p tags, unknown route). Format 1 (no "v") is
     * gone since the signed mailbox (2026-11-18).
     */
    fun targeting(tags0: List<List<String>>): Targeting? {
        val tags = tags0.filter { it.size >= 2 }
        if (tags.none { it[0] == "v" && it[1] == FORMAT2 }) return null
        val route = tags.firstOrNull { it[0] == "route" }?.get(1) ?: "flood"
        if (route !in ROUTES) return null
        val ps = tags.filter { it[0] == "p" }.map { it[1] }
        if (ps.size > MAX_P_TAGS || !ps.all { isLowerHex(it, 32) }) return null
        val rs = tags.filter { it[0] == "r" }.map { it[1] }
        if (rs.size > 1 || !rs.all { isNetwork(it) }) return null
        return Targeting(2, route, ps.distinct().take(MAX_P), rs.firstOrNull())
    }

    /** Hearing proof on an untimed claim: only a bridge that heard report P can make it before P is on the relays. */
    fun claimProof(p: ByteArray, pubkeyHex: String): String = sha256(p + (pubkeyHex.unhex() ?: ByteArray(0))).hex()

    fun hopsClass(hops: Int?): String = when {
        hops == null -> "?"
        hops == 0 -> "0"
        hops <= 3 -> "1-3"
        else -> "4+"
    }

    /**
     * A receipt (PROTOCOL.md 4.1, 0.10.0) only for a report heard directly: on a DIRECT route
     * (`["route", "direct"]`, no hop count, the bridge does not know it) or as a flood with 0 hops (`["hops", "0"]`).
     */
    fun receiptTags(x: String, direct: Boolean, heardAtS: Long): List<List<String>> =
        listOf(listOf("y", x), if (direct) listOf("route", "direct") else listOf("hops", "0"),
            listOf("expiration", (heardAtS + RECEIPT_TTL_S).toString()))

    fun announceTags(
        network: String, nowS: Long, regions: List<String> = emptyList(), preset: List<Long>? = null,
        relays: List<String> = emptyList(), acceptOpen: Boolean = true, receipts: Boolean = true, anchorLeadS: Long = 60,
        lateMaxS: Long = 40, radio: Pair<String, Long>? = null,
    ): List<List<String>> {
        val tags = mutableListOf(listOf("d", network), listOf("k", Lt2.KIND_COMMAND.toString()))
        regions.filter { it.isNotEmpty() }.forEach { tags += listOf("region", it) } // narrow first (0.10.0)
        // the state of the radio itself, not of the software (SPEC 3.2 "Radio health")
        if (radio != null) tags += listOf("radio", radio.first, radio.second.toString())
        if (preset != null) tags += listOf("preset") + preset.map { it.toString() }
        relays.forEach { tags += listOf("relay", it) }
        tags += listOf("signed", anchorLeadS.toString(), lateMaxS.toString())
        tags += listOf("open", if (acceptOpen) "1" else "0")
        tags += listOf("receipts", if (receipts) "1" else "0")
        tags += listOf("expiration", (nowS + ANNOUNCE_TTL_S).toString())
        return tags
    }

    data class Announcement(
        val pubkey: String, val network: String, val region: String?,
        /** Every `region` tag, narrow first. */
        val regions: List<String>,
        /** ["radio", state, since]: (state, since) or null. */
        val radio: Pair<String, Long>?,
        /** ["signed", anchor lead s, late max s] (PROTOCOL.md 4.2), or null. */
        val signed: List<Int>?,
        val open: Boolean, val receipts: Boolean, val expires: Long,
    )

    /** A valid, unexpired announcement's fields (signature checked by the caller), or null. */
    fun parseAnnouncement(ev: NostrEvent, nowS: Long): Announcement? {
        if (ev.kind != ANNOUNCE_KIND) return null
        val tags = ev.tags.filter { it.size >= 2 }
        val get = LinkedHashMap<String, List<String>>()
        for (t in tags) get.putIfAbsent(t[0], t)
        if (get["k"]?.get(1) != Lt2.KIND_COMMAND.toString() || "d" !in get) return null
        val exp = get["expiration"]?.get(1) ?: ""
        if (exp.isEmpty() || exp.length > 12 || !exp.all { it in '0'..'9' } || exp.toLong() <= nowS) return null
        val d = get.getValue("d")[1]
        if (d.isNotEmpty() && !isNetwork(d)) return null
        val sg = get["signed"]?.drop(1)?.take(2)?.filter { it.isNotEmpty() && it.length <= 6 && it.all { c -> c in '0'..'9' } }
            ?.map { it.toInt() }?.takeIf { it.size == 2 }
        val radio = get["radio"]?.takeIf { it.size >= 3 && it[1] in setOf("up", "down") }
            ?.let { r -> r[2].takeIf { it.isNotEmpty() && it.length <= 12 && it.all { c -> c in '0'..'9' } }?.let { r[1] to it.toLong() } }
        return Announcement(ev.pubkey, d, get["region"]?.get(1), tags.filter { it[0] == "region" }.map { it[1] }, radio, sg, (get["open"]?.get(1) ?: "1") == "1",
            (get["receipts"]?.get(1) ?: "0") == "1", exp.toLong())
    }
}

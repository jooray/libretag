package app.nostrmesh.libretag

import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Quiet mesh (LT2, firmware 0.10.0), the parts a bridge needs: the route plan per ACK and command entry,
 * the routed ACK the bridge builds, the second channel data type of a command's fallback, and the
 * transport code of a scoped flood (to find a report's scope). Port of host/src/libretag/quietmesh.py
 * (`plan`, `ack_routed`, `transport_code`, `arrival`), checked against docs/vectors/quiet-mesh-v1.json.
 * PROTOCOL.md 2.7, 3.3, 3.4, 4.3. The bridge holds no key of a tag: it cannot open or make an ACK.
 */
object QuietMesh {
    /** The fallback (second) send of a command: other bytes, so repeaters that saw the first do not drop it. */
    const val DATA_TYPE_RETRY = 0xFF6C
    const val ACK_LEN = 13
    /** ACK entry content: the near form, or near || routed (a flood copy of a standing tag). */
    const val ACK_BOTH_LEN = 26
    /** Routed ACK datagrams on air: Pa || pl || 1 or 2 hashes of 1..3 B. */
    val ROUTED_ACK_LENS = 15..20
    /** `direct_max_hops`: a path of at most two repeaters (0 turns direct routing off). */
    const val PATH_MAX_HOPS = 2
    val HASH_SIZES = 1..3
    /** A command sent zero-hop with `both`, or along a path: flood fallback after this without an answer. */
    const val FALLBACK_AFTER_MS = 12_000L
    /** Claim round before the first routed ACK of a flood (a relay note needs about 1.7 s). */
    const val CLAIM_ROUTED_ACK_MS = 2_000L
    /** Near ACK: random wait, then skip if the same Pa was heard on air. */
    const val ACK_JITTER_MIN_MS = 100L
    const val ACK_JITTER_MAX_MS = 600L
    /** With a command for the same report and window the ACK goes first, the command this much later. */
    const val ACK_BEFORE_CMD_MS = 300L
    /** One timed ACK is sent at most this early. */
    const val ACK_TIMED_EARLY_MS = 2_000L
    const val ACKS_PER_HOUR = 120
    /** Path record and its key lock: from the last use (R33). */
    const val PATH_RECORD_TTL_MS = 48 * 3600_000L
    /** A late entry (or a live ACK entry) for a report heard at most this long ago goes out at once. */
    const val LATE_MAX_MS = 40_000L
    /** A frame heard longer ago than this is approximate: published with `approx`, locks and sends nothing. */
    const val STALE_MS = 20_000L
    /** A `y` of the same entry as `via` (a sibling copy of one report) is an answer only this long after `via`. */
    const val SIBLING_GUARD_MS = 120_000L
    /** Timed send: retried after a radio error every 2 s until `at + 20 s` (the tag listens 30 s). */
    const val TIMED_RETRY_MS = 2_000L
    const val TIMED_RETRY_UNTIL_MS = 20_000L
    /** Blind LOST entries per `via` in 24 h. */
    const val BLIND_PER_DAY = 3
    /** Fast publish of a report heard directly or over a path with nothing waiting. */
    const val FAST_PUBLISH_MIN_MS = 300L
    const val FAST_PUBLISH_MAX_MS = 800L

    const val PAYLOAD_GRP_DATA = 6
    private const val CHANNEL = "#libretag"

    /** How a packet arrived as the plan sees it: [route] flood|tflood|direct|tdirect, [path] first hop first. */
    data class Heard(val route: String, val path: List<String>?, val hopsKnown: Int? = null) {
        val isDirect get() = route == "direct" || route == "tdirect"
        /** Repeaters between the tag and this radio; null for a DIRECT arrival or an unknown path. */
        val hops: Int? get() = when {
            isDirect -> null
            path != null -> path.size
            else -> hopsKnown
        }
    }

    data class Plan(
        val send: String,
        /** Send order, bridge to tag. */
        val path: List<String> = emptyList(),
        val form: String? = null,
        val fallback: Boolean = false,
        val scope: String? = null,
        /** Routed ACK: the path appended for the tag (tag to bridge order). */
        val tagPath: List<String>? = null,
    )

    private val NONE = Plan("none")

    /**
     * How a bridge sends an ACK or command entry for the report (or `via`) it heard (quietmesh.plan).
     * [entryRoute]: commands near|direct|flood|both, ACK entries near|direct. [viaPath]: the path stored for
     * `via` (tag to bridge order), used for a report that arrived DIRECT. [forms] (ACKs): near|both|routed.
     */
    fun plan(
        kind: String, heard: Heard?, entryRoute: String, viaPath: List<String>? = null, forms: String = "near",
        fb: Boolean = true, maxHops: Int = PATH_MAX_HOPS, canZeroHop: Boolean = true, canPath: Boolean = true,
        blind: Boolean = false,
    ): Plan {
        val pathsOk = canPath && maxHops > 0
        if (heard == null) {
            return if (kind == "cmd" && blind) Plan("flood", scope = "country") else NONE
        }
        val isDirect = heard.isDirect
        val hops = heard.hops
        val stored = if (isDirect) viaPath.orEmpty() else heard.path.orEmpty()
        val usable = pathsOk && stored.size in 1..maxHops && (isDirect || heard.path != null)

        if (kind == "ack") {
            if (!isDirect && hops == 0) {
                return if (forms in setOf("near", "both") && canZeroHop) Plan("zero-hop", form = "near") else NONE
            }
            if (isDirect && entryRoute == "near") {
                return if (forms in setOf("near", "both") && canZeroHop) Plan("zero-hop", form = "near") else NONE
            }
            if (entryRoute == "direct" && forms in setOf("both", "routed") && usable) {
                return Plan("path", stored.reversed(), form = "routed", tagPath = stored)
            }
            return NONE // never a flood for an ACK
        }
        val mayFlood = entryRoute == "flood" || entryRoute == "both"
        if (isDirect || hops == 0) {
            if (isDirect && usable && entryRoute != "near") {
                return Plan("path", stored.reversed(), fallback = fb && mayFlood, scope = "report")
            }
            val scope = if (isDirect) "narrowest" else "report" // a DIRECT arrival carries no scope
            if (canZeroHop) return Plan("zero-hop", fallback = fb && entryRoute == "both", scope = scope)
            return if (mayFlood) Plan("flood", scope = scope) else NONE
        }
        if (entryRoute == "near") return NONE
        if (usable) return Plan("path", stored.reversed(), fallback = fb && mayFlood, scope = "report")
        return if (mayFlood) Plan("flood", scope = "report") else NONE
    }

    /** MeshCore's path length byte: (hash size - 1) << 6 | count. */
    fun pathLenByte(hashSize: Int, count: Int): Int {
        require(hashSize in HASH_SIZES && count in 0..63) { "bad path" }
        return (hashSize - 1) shl 6 or count
    }

    /**
     * The routed ACK the bridge sends: Pa (with ROUTED, made by the app) || pl || path, path in the order tag
     * to bridge. The path is not under the mac and never goes through Nostr.
     */
    fun ackRouted(pa: ByteArray, hashSize: Int, path: List<ByteArray>): ByteArray {
        require(pa.size == ACK_LEN && path.size in 1..PATH_MAX_HOPS && path.all { it.size == hashSize }) {
            "routed ACK needs Pa and 1 or 2 path entries of the hash size"
        }
        return pa + byteArrayOf(pathLenByte(hashSize, path.size).toByte()) + path.fold(ByteArray(0)) { a, h -> a + h }
    }

    /** Hex hashes as one byte string (send order kept). */
    fun pathBytes(path: List<String>): ByteArray = path.fold(ByteArray(0)) { a, h -> a + (h.unhex() ?: ByteArray(0)) }

    /** A path of [hashSize]-byte hashes as hex strings, first hop first; null when the bytes do not split. */
    fun hashes(path: ByteArray?, hashSize: Int?): List<String>? {
        if (path == null) return null
        val hs = hashSize ?: return if (path.isEmpty()) emptyList() else null
        if (hs !in HASH_SIZES || path.size % hs != 0) return null
        return path.toList().chunked(hs).map { it.toByteArray().hex() }
    }

    // ------------------------------------------------------------------ scopes (PROTOCOL.md 2.7)

    /** MeshCore region key: SHA-256("#" + name)[0:16] (a leading '#' is not doubled). */
    fun scopeKey(name: String): ByteArray = sha256((if (name.startsWith("#")) name else "#$name").toByteArray()).copyOf(16)

    /** code[0] = HMAC-SHA256(key, payload_type || payload)[0:2] LE, 0 -> 1, 0xFFFF -> 0xFFFE. */
    fun transportCode(name: String, payloadType: Int, payload: ByteArray): Int {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(scopeKey(name), "HmacSHA256"))
        m.update(payloadType.toByte())
        val h = m.doFinal(payload)
        val c = (h[0].toInt() and 0xFF) or ((h[1].toInt() and 0xFF) shl 8)
        return when (c) { 0 -> 1; 0xFFFF -> 0xFFFE; else -> c }
    }

    private val channelKey: ByteArray = sha256(CHANNEL.toByteArray()).copyOf(16)

    /**
     * The GRP_DATA payload of datagram [inner] on #libretag under [dataType]. The channel key is public and
     * the envelope deterministic, so a bridge rebuilds the payload of a heard report to test scopes.
     */
    fun channelWrap(inner: ByteArray, dataType: Int): ByteArray {
        val head = byteArrayOf(dataType.toByte(), (dataType shr 8).toByte(), inner.size.toByte())
        var pt = head + inner
        if (pt.size % 16 != 0) pt += ByteArray(16 - pt.size % 16)
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(channelKey, "AES"))
        val ct = c.doFinal(pt)
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(channelKey + ByteArray(16), "HmacSHA256"))
        val mac = m.doFinal(ct).copyOf(2)
        return byteArrayOf(sha256(channelKey)[0]) + mac + ct
    }

    /**
     * The scope a report flooded in (PROTOCOL.md 4.3 "Reply scope"): the name the node resolved when it is one
     * of [regions], else the first of [regions] whose transport code matches [transportCode]; null when none
     * does or nothing is known.
     */
    fun replyScope(scope: String?, transportCode: Int?, dataType: Int, datagram: ByteArray, regions: List<String>): String? {
        if (scope != null && scope.trim().removePrefix("#") in regions.map { it.removePrefix("#") }) return scope.trim()
        val code = transportCode ?: return null
        val payload = channelWrap(datagram, dataType)
        return regions.firstOrNull { transportCode(it, PAYLOAD_GRP_DATA, payload) == code }
    }

    /** The form an ACK entry asks for, from its content length and route (PROTOCOL.md 4.3). */
    fun ackForms(contentLen: Int, route: String): String? = when {
        contentLen == ACK_BOTH_LEN && route == "direct" -> "both"
        contentLen == ACK_LEN && route == "near" -> "near"
        contentLen == ACK_LEN && route == "direct" -> "routed"
        else -> null
    }
}

package app.nostrmesh.bridge

import io.github.jooray.meshcore.localapi.Capabilities
import kotlin.math.ceil
import kotlin.math.max

/** How a datagram leaves the radio (Local API `channel.sendData` `path`, SPEC 6.2). */
sealed interface Route {
    /** No `path` field: MeshCore floods. */
    data object Flood : Route
    /** `"path": ""`: only radios in range hear it, nobody repeats it. */
    data object ZeroHop : Route
    /** Along [path] (repeater hashes in sending order, [hashSize] bytes each). */
    class Direct(val path: ByteArray, val hashSize: Int) : Route {
        val hops get() = path.size / hashSize
        override fun toString() = "Direct(${path.joinToString("") { "%02x".format(it) }}/$hashSize)"
    }
}

/** Local API capability names (SPEC section 13 of github.com/jooray/meshcore-local-api). */
object Caps {
    const val TX_PATH_DATA = Capabilities.TX_PATH_DATA
    const val TX_PATH_TEXT = Capabilities.TX_PATH_TEXT
    const val RX_PATH = Capabilities.RX_PATH
    const val RX_META = Capabilities.RX_META
    const val RAW_RX = Capabilities.RAW_RX
    const val RAW_TX = Capabilities.RAW_TX
    const val TX_RESULT = Capabilities.TX_RESULT
    const val AIRTIME = Capabilities.AIRTIME
    const val DUTY_CAP = Capabilities.DUTY_CAP
    const val NOISE_FLOOR = Capabilities.NOISE_FLOOR
    const val RADIO_STATS = Capabilities.RADIO_STATS
    const val REGIONS = Capabilities.REGIONS
}

/**
 * What the server said about one received datagram. [path] is the flood path as it arrived (first
 * hop first), only when the server advertises `rx_path`. [receivedAtMs] is the server's receive
 * time; older than a few seconds means a replay from the `since` cursor.
 */
data class RxMeta(
    val hops: Int? = null,
    val path: ByteArray? = null,
    val pathHashSize: Int? = null,
    val routeType: String? = null,
    val snrDb: Double? = null,
    val rssiDbm: Double? = null,
    val receivedAtMs: Long? = null,
) {
    /** Repeaters between the sender and this radio; 0 = heard directly; null = unknown. */
    fun hopCount(caps: Set<String>): Int? {
        if (Caps.RX_PATH in caps && path != null) {
            if (path.isEmpty()) return 0
            val hs = pathHashSize ?: return null
            return path.size / hs
        }
        return hops
    }

    /** The path back to the sender (hops reversed), when the server reported one. */
    fun reversePath(caps: Set<String>): Route.Direct? {
        if (Caps.RX_PATH !in caps) return null
        val p = path ?: return null
        val hs = pathHashSize ?: return null
        if (p.isEmpty() || p.size % hs != 0) return null
        val hops = p.toList().chunked(hs).reversed().flatten().toByteArray()
        return Route.Direct(hops, hs)
    }
}

/**
 * Route for a downlink to a device that just spoke (SPEC 6.2, review point 1): zero-hop when it
 * was heard directly (also for an allowed flood, as lt2svc.py does), else flood when the caller
 * allows it (LibreTag LOST), else direct along the reversed path up to [maxHops], otherwise
 * nothing. Without `tx_path_data` an old server would flood whatever we ask, so only an allowed
 * flood remains.
 */
fun downlinkRoute(meta: RxMeta, caps: Set<String>, floodAllowed: Boolean, maxHops: Int = 3): Route? {
    val pathOk = Caps.TX_PATH_DATA in caps
    val hops = meta.hopCount(caps)
    if (pathOk && hops == 0) return Route.ZeroHop
    if (floodAllowed) return Route.Flood
    if (!pathOk || hops == null) return null
    if (hops > maxHops) return null
    return meta.reversePath(caps)?.takeIf { it.hops == hops }
}

/**
 * Rate-limit key of a heard #libretag packet (host lt2svc.first_hop_key): "direct" when heard
 * without a repeater, hash size + first repeater hash when the server reports the path, null when
 * it does not (only the global #libretag limit applies).
 */
fun firstHopKey(meta: RxMeta, caps: Set<String>): String? {
    val hops = meta.hopCount(caps)
    if (hops == 0) return "direct"
    if (Caps.RX_PATH !in caps) return null
    val p = meta.path ?: return null
    val hs = meta.pathHashSize ?: return null
    if (p.isEmpty() || p.size < hs) return null
    return "$hs:" + p.copyOf(hs).joinToString("") { "%02x".format(it) }
}

/**
 * Route for an LT2 command (PROTOCOL.md 4, since 0.7.3): zero-hop when the tracker's report was
 * heard directly and the server can send zero-hop, otherwise flood (region-scoped as the server
 * does). Never direct along a path: the tracker may have moved, and commands are rare enough that
 * the flood costs little.
 */
fun lt2CommandRoute(hops: Int?, caps: Set<String>): Route =
    if (hops == 0 && Caps.TX_PATH_DATA in caps) Route.ZeroHop else Route.Flood

/**
 * Route for a mailbox entry (PROTOCOL.md 4.1), or null when the entry's route does not allow this
 * bridge to send it. The route binds:
 * `near` = zero-hop only when heard directly; `direct` = as near, or (untimed only) along [reverse],
 * the reversed path of a report heard over 1 to [directMaxHops] hops; `flood` = zero-hop when heard directly, else flood. A timed
 * entry treats `direct` as `near` (its path is hours old).
 */
fun lt2EntryRoute(
    fmt: Int, route: String, timed: Boolean, hops: Int?, reverse: Route.Direct?, caps: Set<String>,
    directMaxHops: Int = 3,
): Route? {
    val zeroHop = hops == 0 && Caps.TX_PATH_DATA in caps
    if (fmt != 2 || route == "flood") return lt2CommandRoute(hops, caps)
    if (zeroHop) return Route.ZeroHop
    if (route != "direct" || timed || hops == null || hops !in 1..directMaxHops || Caps.TX_PATH_DATA !in caps) return null
    return reverse?.takeIf { it.hops == hops }
}

/** LoRa modem settings; [cr] is the coding-rate denominator (5 = 4/5 … 8 = 4/8). */
data class LoRaPreset(val sf: Int, val bwHz: Double, val cr: Int) {
    val valid get() = sf in 5..12 && bwHz > 0 && cr in 5..8

    companion object {
        /** Slovakia / Hungary / Czechia (SPEC 6.4). */
        val SK = LoRaPreset(7, 62_500.0, 5)

        /** "7/62.5/5" (BW in kHz). */
        fun parse(s: String): LoRaPreset? {
            val p = s.trim().split('/', ' ', ',').filter { it.isNotEmpty() }
            if (p.size != 3) return null
            val sf = p[0].toIntOrNull() ?: return null
            val bw = p[1].toDoubleOrNull() ?: return null
            val cr = p[2].toIntOrNull() ?: return null
            return LoRaPreset(sf, bw * 1000, if (cr in 1..4) cr + 4 else cr).takeIf { it.valid }
        }
    }

    override fun toString() = "$sf/${bwHz / 1000}/$cr"
}

object Airtime {
    const val PREAMBLE = 16

    /** Semtech time-on-air (explicit header, CRC on) in ms. */
    fun ms(bytes: Int, p: LoRaPreset, preamble: Int = PREAMBLE): Double {
        val tSym = (1 shl p.sf) / p.bwHz * 1000.0
        val de = if (tSym > 16.0) 1 else 0
        val num = 8.0 * bytes - 4 * p.sf + 28 + 16
        val payloadSym = 8 + max(ceil(num / (4 * (p.sf - 2 * de))) * p.cr, 0.0)
        return (preamble + 4.25) * tSym + payloadSym * tSym
    }

    /**
     * MeshCore packet carrying a channel datagram of [dataLen] bytes: header 1, path_len 1, path,
     * region transport codes 4, GRP_DATA payload = channel hash 1 + MAC 2 + ciphertext of
     * data_type 2 + len 1 + data, padded to 16.
     */
    fun channelPacketBytes(dataLen: Int, pathBytes: Int = 0, region: Boolean = false): Int {
        val ct = ((3 + dataLen + 15) / 16) * 16
        return 1 + (if (region) 4 else 0) + 1 + pathBytes + 1 + 2 + ct
    }

    /** Transmissions for one send: a direct path is repeated once per hop. */
    fun transmissions(route: Route): Int = when (route) {
        is Route.Direct -> route.hops + 1
        else -> 1
    }
}

/**
 * Rolling one-hour budget for the bridge's own transmissions (review point 6: bytes × sends, not
 * message counts). [fraction] 0.001 = 0.1 % = 3.6 s per hour.
 */
class AirtimeBudget(var fraction: Double = 0.001, private val windowMs: Long = 3600_000L) {
    private val log = ArrayDeque<Pair<Long, Double>>()

    val budgetMs get() = fraction * windowMs

    @Synchronized
    fun usedMs(now: Long): Double {
        while (log.isNotEmpty() && now - log.first().first >= windowMs) log.removeFirst()
        return log.sumOf { it.second }
    }

    @Synchronized
    fun allow(now: Long, ms: Double) = usedMs(now) + ms <= budgetMs

    /** Records [ms] if it fits; false (nothing recorded) otherwise. */
    @Synchronized
    fun spend(now: Long, ms: Double): Boolean {
        if (!allow(now, ms)) return false
        log.addLast(now to ms)
        return true
    }

    @Synchronized
    fun export(): List<Pair<Long, Double>> = log.toList()

    @Synchronized
    fun restore(entries: List<Pair<Long, Double>>, now: Long) {
        log.clear()
        entries.filter { now - it.first < windowMs && it.first <= now }.sortedBy { it.first }.forEach { log.addLast(it) }
    }
}

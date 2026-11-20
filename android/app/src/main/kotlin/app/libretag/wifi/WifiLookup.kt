package app.libretag.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * App side of Wi-Fi positioning (docs/design/wifi-positioning.md). The tracker sends one full BSSID (the
 * "seed") and the other APs as their last 3 bytes ("tails"); the app asks a service for the seed,
 * gets the APs around it and matches the tails locally. The report layout is not final, so the
 * input here is abstract ([WifiObservation]).
 */

/** A full BSSID the tracker heard. [mac] is "aa:bb:cc:dd:ee:ff". */
data class WifiAp(val mac: String, val rssiDbm: Int? = null)

/** The last 3 bytes of a BSSID, "dd:ee:ff". */
data class WifiTail(val tail: String, val rssiDbm: Int? = null)

/** What one report (or scan) says about Wi-Fi: the seed, the tails, and any further full MACs. */
data class WifiObservation(
    val seed: WifiAp?,
    val tails: List<WifiTail> = emptyList(),
    val others: List<WifiAp> = emptyList(),
) {
    /** every full MAC, seed first */
    val fullMacs: List<WifiAp> get() = listOfNotNull(seed) + others
}

/** Where a service puts an AP. [accuracyM] is the service's own radius. */
data class ApPosition(val mac: String, val lat: Double, val lon: Double, val accuracyM: Double)

/** A position computed by the service itself (BeaconDB geolocate). */
data class DirectFix(val lat: Double, val lon: Double, val accuracyM: Double)

/** What a lookup returned: AP positions (Apple) and/or a position (BeaconDB); [unknown] MACs have none. */
data class LookupResult(
    val aps: List<ApPosition> = emptyList(),
    val direct: DirectFix? = null,
    val unknown: List<String> = emptyList(),
)

enum class WifiService(val endpoint: String?) {
    OFF(null),

    /** MLS-style geolocate; needs at least 2 full MACs, returns only a position */
    BEACONDB("https://api.beacondb.net/v1/geolocate"),

    /** GrapheneOS' proxy for Apple's service (Apple does not see the user's IP) */
    GRAPHENEOS_PROXY("https://gs-loc.apple.grapheneos.org/clls/wloc"),

    /** Apple directly */
    APPLE("https://gs-loc.apple.com/clls/wloc"),
    ;

    val isApple: Boolean get() = this == GRAPHENEOS_PROXY || this == APPLE
}

object Mac {
    private val SEP = Regex("[:\\-.]")

    /** "A:B:C:D:E:F", "aa-bb-…", "aabbccddeeff" -> "0a:0b:…" lowercase; null if not 6 octets. */
    fun normalize(s: String): String? = octets(s, 6)

    /** "DD:EE:FF" or "ddeeff" -> "dd:ee:ff"; null if not 3 octets. */
    fun normalizeTail(s: String): String? = octets(s, 3)

    fun tail(mac: String): String = normalize(mac)!!.substring(9)

    /** Locally administered (bit 1 of the first octet): random or virtual, not unique worldwide. */
    fun isLocallyAdministered(mac: String): Boolean = (normalize(mac)!!.substring(0, 2).toInt(16) and 0x02) != 0

    private fun octets(s: String, count: Int): String? {
        val t = s.trim().lowercase()
        val parts = if (SEP.containsMatchIn(t)) t.split(SEP) else if (t.length == 2 * count) t.chunked(2) else return null
        if (parts.size != count) return null
        if (parts.any { it.isEmpty() || it.length > 2 || it.any { c -> c !in "0123456789abcdef" } }) return null
        return parts.joinToString(":") { it.padStart(2, '0') }
    }
}

/** A minimal protobuf writer/reader: varints, length-delimited fields, nothing else needed here. */
object Protobuf {
    const val VARINT = 0
    const val I64 = 1
    const val LEN = 2
    const val I32 = 5

    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(v: Long): Writer {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
            return this
        }

        private fun tag(field: Int, type: Int) = varint(((field shl 3) or type).toLong())

        fun int(field: Int, v: Long): Writer = tag(field, VARINT).varint(v)

        fun bytes(field: Int, b: ByteArray): Writer {
            tag(field, LEN).varint(b.size.toLong())
            out.write(b)
            return this
        }

        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))

        fun message(field: Int, m: Writer) = bytes(field, m.toByteArray())

        /** packed repeated varints (proto3 default for repeated scalars) */
        fun packed(field: Int, values: List<Long>): Writer {
            val w = Writer()
            values.forEach { w.varint(it) }
            return bytes(field, w.toByteArray())
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** One decoded field: [varint] for VARINT/I64/I32 (raw bits), [bytes] for LEN. */
    class Field(val number: Int, val type: Int, val varint: Long, val bytes: ByteArray?) {
        fun string() = String(bytes!!, Charsets.UTF_8)
    }

    /** All top-level fields of a message; throws IOException on malformed input. */
    fun fields(b: ByteArray, from: Int = 0, to: Int = b.size): List<Field> {
        val out = mutableListOf<Field>()
        var i = from
        fun readVarint(): Long {
            var shift = 0
            var r = 0L
            while (true) {
                if (i >= to || shift > 63) throw IOException("truncated varint")
                val c = b[i++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return r
                shift += 7
            }
        }
        while (i < to) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            val type = (key and 7).toInt()
            if (number == 0) throw IOException("field 0")
            when (type) {
                VARINT -> out += Field(number, type, readVarint(), null)
                I64 -> {
                    if (i + 8 > to) throw IOException("truncated i64")
                    var v = 0L
                    for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                    i += 8
                    out += Field(number, type, v, null)
                }
                LEN -> {
                    val len = readVarint()
                    if (len < 0 || i + len > to) throw IOException("truncated bytes")
                    out += Field(number, type, 0, b.copyOfRange(i, i + len.toInt()))
                    i += len.toInt()
                }
                I32 -> {
                    if (i + 4 > to) throw IOException("truncated i32")
                    var v = 0L
                    for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                    i += 4
                    out += Field(number, type, v, null)
                }
                else -> throw IOException("wire type $type")
            }
        }
        return out
    }
}

/**
 * Apple's Wi-Fi location service (`/clls/wloc`), as GrapheneOS NetworkLocation talks to it
 * (proto/apple_wps.proto, ApplePositioningService.kt): a big-endian header, then
 * ALSLocationRequest; the reply has 10 bytes of header, then ALSLocationResponse.
 */
object AppleWloc {
    const val CONTENT_TYPE = "application/x-www-form-urlencoded"
    const val USER_AGENT = "locationd/2960.0.57 CFNetwork/3826.500.111.1.1 Darwin/24.4.0"
    const val LOCALE = "en-US_US"
    const val IDENTIFIER = "com.apple.locationd"
    const val VERSION = "15.4.24E248"
    const val SOFTWARE_BUILD = "macOS15.4/24E248"
    const val PRODUCT_ID = "arm64"
    const val MAX_REQUEST_MACS = 40
    const val RESPONSE_HEADER = 10
    const val UNKNOWN_LAT = -18_000_000_000L
    const val SCALE = 1e-8

    /** ALSLocationRequest { 2: wireless_aps[{1: mac}], 4: surrounding, 31: bands [1,2], 32: 2, 33: meta }. */
    fun requestProto(macs: List<String>, surrounding: Int = 100): ByteArray {
        val w = Protobuf.Writer()
        macs.take(MAX_REQUEST_MACS).forEach { w.message(2, Protobuf.Writer().string(1, it)) }
        w.int(4, maxOf(1, surrounding).toLong()) // 0 means "about 400"
        w.packed(31, listOf(1L, 2L)) // 2.4 and 5 GHz
        w.int(32, 2) // altitude scale 10^2
        w.message(33, Protobuf.Writer().string(1, SOFTWARE_BUILD).string(2, PRODUCT_ID))
        return w.toByteArray()
    }

    /** The HTTP body: u16 1, u16+locale, u16+identifier, u16+version, u32 1, u32 length, proto. */
    fun requestBody(macs: List<String>, surrounding: Int = 100): ByteArray {
        val norm = macs.mapNotNull { Mac.normalize(it) }.distinct()
        require(norm.isNotEmpty()) { "no valid MAC" }
        val proto = requestProto(norm, surrounding)
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun str(s: String) { val b = s.toByteArray(Charsets.US_ASCII); u16(b.size); out.write(b) }
        u16(1)
        str(LOCALE)
        str(IDENTIFIER)
        str(VERSION)
        u32(1)
        u32(proto.size)
        out.write(proto)
        return out.toByteArray()
    }

    /** Parses the HTTP body; MACs come back without leading zeros per octet and are normalised. */
    fun parseResponse(body: ByteArray): LookupResult {
        if (body.size < RESPONSE_HEADER) throw IOException("short response")
        val aps = LinkedHashMap<String, ApPosition>()
        val unknown = LinkedHashSet<String>()
        for (f in Protobuf.fields(body, RESPONSE_HEADER)) {
            if (f.number != 2 || f.type != Protobuf.LEN) continue
            var mac: String? = null
            var loc: ByteArray? = null
            for (g in Protobuf.fields(f.bytes!!)) {
                if (g.number == 1 && g.type == Protobuf.LEN) mac = Mac.normalize(g.string())
                if (g.number == 2 && g.type == Protobuf.LEN) loc = g.bytes
            }
            if (mac == null) continue
            var lat: Long? = null
            var lon: Long? = null
            var acc = 0L
            loc?.let { l ->
                for (g in Protobuf.fields(l)) when {
                    g.number == 1 && g.type == Protobuf.VARINT -> lat = g.varint
                    g.number == 2 && g.type == Protobuf.VARINT -> lon = g.varint
                    g.number == 3 && g.type == Protobuf.VARINT -> acc = g.varint.toInt().toLong()
                }
            }
            val la = lat
            val lo = lon
            if (la == null || lo == null || la == UNKNOWN_LAT || acc < 0) {
                if (mac !in aps) unknown += mac
                continue
            }
            val p = ApPosition(mac, la * SCALE, lo * SCALE, acc.toDouble())
            if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) { unknown += mac; continue }
            aps.putIfAbsent(mac, p)
            unknown -= mac
        }
        return LookupResult(aps = aps.values.toList(), unknown = unknown.toList())
    }
}

/** BeaconDB (MLS / Google geolocate API). Needs at least two full MACs; returns only a position. */
object BeaconDb {
    const val MIN_APS = 2

    fun requestJson(aps: List<WifiAp>): String {
        val norm = aps.mapNotNull { a -> Mac.normalize(a.mac)?.let { a.copy(mac = it) } }.distinctBy { it.mac }
        require(norm.size >= MIN_APS) { "BeaconDB needs at least $MIN_APS full MACs" }
        val o = buildJsonObject {
            put("considerIp", false)
            put("wifiAccessPoints", buildJsonArray {
                norm.forEach { a ->
                    add(buildJsonObject {
                        put("macAddress", a.mac)
                        a.rssiDbm?.let { put("signalStrength", it) }
                    })
                }
            })
        }
        return Json.encodeToString(JsonObject.serializer(), o)
    }

    /** BeaconDB has no position for these networks (HTTP 404): every queried MAC is a known unknown. */
    fun notFound(aps: List<WifiAp>): LookupResult =
        LookupResult(unknown = aps.mapNotNull { Mac.normalize(it.mac) }.distinct())

    /** {"location":{"lat":…,"lng":…},"accuracy":…} -> fix; an error object or garbage -> null. */
    fun parseResponse(body: String): DirectFix? = try {
        val o = Json.parseToJsonElement(body).jsonObject
        val loc = o["location"]!!.jsonObject
        DirectFix(loc["lat"]!!.jsonPrimitive.double, loc["lng"]!!.jsonPrimitive.double, o["accuracy"]!!.jsonPrimitive.double)
            .takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.accuracyM > 0 }
    } catch (e: Exception) {
        null
    }
}

/** The service answered with an HTTP error; [code] 429 means it is limiting our queries. */
class WifiHttpException(val code: Int) : IOException("HTTP $code") {
    val rateLimited: Boolean get() = code == 429
}

/**
 * The only class here that talks to the network. The service sees the queried MACs and the
 * phone's IP (the GrapheneOS proxy sees both, Apple then only the proxy). Never called by tests.
 */
class WifiLookupClient(private val service: WifiService, private val http: OkHttpClient) {

    /**
     * Looks up the observation's full MACs; null when the service is off or cannot answer this
     * (too few MACs). An HTTP error throws [WifiHttpException] (429: rate limited). BeaconDB's 404
     * means it has no position for these networks: a result with every MAC unknown
     * ([BeaconDb.notFound]), so the cache remembers that and does not ask again.
     */
    suspend fun lookup(obs: WifiObservation, surrounding: Int = 100): LookupResult? = withContext(Dispatchers.IO) {
        val url = service.endpoint ?: return@withContext null
        val full = obs.fullMacs
        when {
            service == WifiService.BEACONDB -> {
                if (full.size < BeaconDb.MIN_APS) return@withContext null
                val req = Request.Builder().url(url)
                    .header("User-Agent", "LibreTag")
                    .post(BeaconDb.requestJson(full).toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (r.code == 404) return@withContext BeaconDb.notFound(full)
                    if (!r.isSuccessful) throw WifiHttpException(r.code)
                    BeaconDb.parseResponse(r.body?.string() ?: "")?.let { LookupResult(direct = it) }
                        ?: throw IOException("unreadable answer")
                }
            }
            service.isApple -> {
                if (full.isEmpty()) return@withContext null
                val req = Request.Builder().url(url)
                    .header("User-Agent", AppleWloc.USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .post(AppleWloc.requestBody(full.map { it.mac }, surrounding).toRequestBody(AppleWloc.CONTENT_TYPE.toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw WifiHttpException(r.code)
                    AppleWloc.parseResponse(r.body?.bytes() ?: ByteArray(0))
                }
            }
            else -> null
        }
    }
}

/** A tail matched to a known AP, with the RSSI the tracker measured. */
data class MatchedAp(val ap: ApPosition, val rssiDbm: Int?)

data class TailMatch(
    /** seed and other full MACs found, then matched tails */
    val matched: List<MatchedAp>,
    /** tails that fit APs too far apart to choose */
    val ambiguous: List<String>,
    val unmatched: List<String>,
)

/** Matches 3-byte tails against the APs around the seed (the false-match rate is ~0.002 % per tail). */
object TailMatcher {
    /** candidates closer than this are one place (several radios of one router), not ambiguity */
    const val SAME_PLACE_M = 60.0

    fun match(obs: WifiObservation, known: List<ApPosition>): TailMatch {
        val byMac = known.associateBy { it.mac }
        val byTail = known.groupBy { Mac.tail(it.mac) }
        val matched = mutableListOf<MatchedAp>()
        val used = mutableSetOf<String>()
        for (a in obs.fullMacs) {
            val m = Mac.normalize(a.mac) ?: continue
            byMac[m]?.let { if (used.add(m)) matched += MatchedAp(it, a.rssiDbm) }
        }
        val ambiguous = mutableListOf<String>()
        val unmatched = mutableListOf<String>()
        for (t in obs.tails) {
            val tail = Mac.normalizeTail(t.tail) ?: continue
            val cands = byTail[tail].orEmpty().filter { it.mac !in used }
            when {
                cands.isEmpty() -> unmatched += tail
                cands.size == 1 -> { used += cands[0].mac; matched += MatchedAp(cands[0], t.rssiDbm) }
                spread(cands) <= SAME_PLACE_M -> {
                    cands.forEach { used += it.mac }
                    matched += MatchedAp(
                        ApPosition(cands[0].mac, cands.map { it.lat }.average(), cands.map { it.lon }.average(),
                            cands.maxOf { it.accuracyM }),
                        t.rssiDbm,
                    )
                }
                else -> ambiguous += tail
            }
        }
        return TailMatch(matched, ambiguous, unmatched)
    }

    private fun spread(c: List<ApPosition>): Double {
        var m = 0.0
        for (i in c.indices) for (j in i + 1 until c.size) {
            m = maxOf(m, app.libretag.locate.Geo.distanceM(c[i].lat, c[i].lon, c[j].lat, c[j].lon))
        }
        return m
    }
}

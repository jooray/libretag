package app.nostrmesh.frames

import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Roadbeacon/Roadstr compact frames (roadbeacon nips/road-events.md), decode side. */
object Road {
    const val KIND_REPORT = 1315
    const val KIND_VOTE = 1316
    val TYPES = mapOf(
        0 to "police", 1 to "speed_camera", 2 to "traffic_jam", 3 to "accident", 4 to "road_closure",
        5 to "construction", 6 to "hazard", 7 to "road_condition", 8 to "pothole", 9 to "fog", 10 to "ice",
        11 to "animal", 12 to "police_station", 13 to "surveillance_camera", 255 to "other",
    )
    val LIFETIME_S = mapOf(
        "police" to 4 * 3600L, "speed_camera" to 30 * 86400L, "traffic_jam" to 3600L, "accident" to 4 * 3600L,
        "road_closure" to 24 * 3600L, "construction" to 15 * 86400L, "hazard" to 4 * 3600L,
        "road_condition" to 15 * 86400L, "pothole" to 7 * 86400L, "fog" to 4 * 3600L, "ice" to 4 * 3600L,
        "animal" to 3600L, "police_station" to 30 * 86400L, "surveillance_camera" to 30 * 86400L, "other" to 4 * 3600L,
    )
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun fmtE6(v: Int): String {
        val m = Math.abs(v.toLong())
        return (if (v < 0) "-" else "") + "${m / 1_000_000}." + (m % 1_000_000).toString().padStart(6, '0')
    }

    fun geohash(lat: Double, lon: Double, precision: Int): String {
        val latR = doubleArrayOf(-90.0, 90.0)
        val lonR = doubleArrayOf(-180.0, 180.0)
        val sb = StringBuilder()
        var bit = 0
        var ch = 0
        var isLon = true
        while (sb.length < precision) {
            val r = if (isLon) lonR else latR
            val v = if (isLon) lon else lat
            val mid = (r[0] + r[1]) / 2
            if (v >= mid) { ch = ch or (1 shl (4 - bit)); r[0] = mid } else r[1] = mid
            isLon = !isLon
            if (++bit == 5) { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    fun reportTags(lat: Int, lon: Int, type: String, expiration: Long, maxspeed: Int): List<List<String>> {
        val la = lat / 1e6
        val lo = lon / 1e6
        val t = mutableListOf(listOf("lat", fmtE6(lat)), listOf("lon", fmtE6(lon)))
        for (p in 4..6) t.add(listOf("g", geohash(la, lo, p)))
        t.add(listOf("t", type))
        t.add(listOf("expiration", expiration.toString()))
        if (maxspeed != 0) t.add(listOf("maxspeed", maxspeed.toString()))
        return t
    }

    fun decode(f: ByteArray): NostrEvent {
        if (f.isEmpty()) throw FrameException("BAD_LENGTH")
        val flags = f[0].toInt() and 0xFF
        if (flags shr 4 != 0) throw FrameException("BAD_VERSION")
        if (flags and 0x03 != 0) throw FrameException("RESERVED_BITS")
        val bb = ByteBuffer.wrap(f)
        if (flags and 0x08 != 0) {
            if (f.size != 134) throw FrameException("BAD_LENGTH")
            val st = f[69].toInt() and 0xFF
            if (st > 1) throw FrameException("BAD_STATUS")
            return NostrEvent(f.copyOfRange(1, 33).hex(), bb.getInt(33).toLong() and 0xFFFFFFFFL, KIND_VOTE,
                listOf(listOf("e", f.copyOfRange(37, 69).hex()), listOf("status", if (st == 1) "still_there" else "no_longer_there")),
                "", sig = f.copyOfRange(70, 134).hex()).withId()
        }
        if (f.size < 117) throw FrameException("BAD_LENGTH")
        val n = f[52].toInt() and 0xFF
        if (n > 48 || f.size != 117 + n) throw FrameException("BAD_LENGTH")
        val lat = bb.getInt(37)
        val lon = bb.getInt(41)
        val type = TYPES[f[45].toInt() and 0xFF] ?: throw FrameException("UNKNOWN_TYPE")
        if (lat !in -90_000_000..90_000_000 || lon !in -180_000_000..180_000_000) throw FrameException("COORDINATE_RANGE")
        val exp = bb.getInt(46).toLong() and 0xFFFFFFFFL
        val ms = bb.getShort(50).toInt() and 0xFFFF
        if (ms != 0 && ms !in 5..300) throw FrameException("BAD_SPEED_LIMIT")
        val comment = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(f, 53, n)).toString()
        } catch (e: CharacterCodingException) {
            throw FrameException("NON_CANONICAL")
        }
        if (comment.any { it.code < 0x20 || it.code == 0x7F }) throw FrameException("NON_CANONICAL")
        return NostrEvent(f.copyOfRange(1, 33).hex(), bb.getInt(33).toLong() and 0xFFFFFFFFL, KIND_REPORT,
            reportTags(lat, lon, type, exp, ms), comment, sig = f.copyOfRange(53 + n, f.size).hex()).withId()
    }
}

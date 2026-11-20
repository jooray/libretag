package app.nostrmesh.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray? {
    if (length % 2 != 0 || any { it !in "0123456789abcdefABCDEF" }) return null
    return ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

fun isLowerHex(s: String, bytes: Int) = s.length == 2 * bytes && s.all { it in "0123456789abcdef" }

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

/** NIP-01 event. The bridge never signs or changes one; [sign] exists for tests. */
data class NostrEvent(
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val id: String = "",
    val sig: String = "",
) {
    fun serialize(): ByteArray {
        val arr = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
            add(JsonPrimitive(content))
        }
        return Json.encodeToString(JsonArray.serializer(), arr).toByteArray(Charsets.UTF_8)
    }

    fun computeId(): String = sha256(serialize()).hex()

    /** Id matches the content and the BIP-340 signature verifies. */
    fun verify(): Boolean {
        val pk = pubkey.unhex() ?: return false
        val s = sig.unhex() ?: return false
        if (pk.size != 32 || s.size != 64 || computeId() != id) return false
        return try {
            Secp256k1.verifySchnorr(s, id.unhex()!!, pk)
        } catch (e: Exception) {
            false
        }
    }

    fun withId(): NostrEvent = copy(id = computeId())

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", buildJsonArray { tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
        put("content", content)
        put("sig", sig)
    }

    companion object {
        fun fromJson(o: JsonObject): NostrEvent? = try {
            NostrEvent(
                pubkey = o["pubkey"]!!.jsonPrimitive.content,
                createdAt = o["created_at"]!!.jsonPrimitive.long,
                kind = o["kind"]!!.jsonPrimitive.int,
                tags = o["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } },
                content = o["content"]!!.jsonPrimitive.content,
                id = o["id"]?.jsonPrimitive?.content ?: "",
                sig = o["sig"]?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            null
        }

        fun sign(sk: ByteArray, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
            val ev = NostrEvent(xonly(sk).hex(), createdAt, kind, tags, content).withId()
            return ev.copy(sig = Secp256k1.signSchnorr(ev.id.unhex()!!, sk, ByteArray(32)).hex())
        }

        fun xonly(sk: ByteArray): ByteArray = Secp256k1.pubkeyCreate(sk).copyOfRange(1, 33)
    }
}

/** NIP-04 content `base64(ct)?iv=base64(iv)`, canonical form only (so frames rebuild exactly). */
object Nip04 {
    fun content(ct: ByteArray, iv: ByteArray): String =
        Base64.getEncoder().encodeToString(ct) + "?iv=" + Base64.getEncoder().encodeToString(iv)

    fun parse(content: String): Pair<ByteArray, ByteArray>? {
        val parts = content.split("?iv=")
        if (parts.size != 2) return null
        val ct: ByteArray
        val iv: ByteArray
        try {
            ct = Base64.getDecoder().decode(parts[0])
            iv = Base64.getDecoder().decode(parts[1])
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (iv.size != 16 || ct.isEmpty() || ct.size % 16 != 0 || content(ct, iv) != content) return null
        return ct to iv
    }
}

/** NIP-19 npub/nsec <-> bytes. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(v: List<Int>): Int {
        var chk = 1
        for (x in v) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor x
            for (i in 0 until 5) if ((b shr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(h: String) = h.map { it.code shr 5 } + 0 + h.map { it.code and 31 }

    private fun convert(data: List<Int>, from: Int, to: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Int>()
        val max = (1 shl to) - 1
        for (v in data) {
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc shr bits) and max)
            }
        }
        if (pad && bits > 0) out.add((acc shl (to - bits)) and max)
        else if (!pad && (bits >= from || ((acc shl (to - bits)) and max) != 0)) return null
        return out
    }

    fun encode(hrp: String, data: ByteArray): String {
        val d = convert(data.map { it.toInt() and 0xff }, 8, 5, true)!!
        val chk = polymod(hrpExpand(hrp) + d + List(6) { 0 }) xor 1
        return hrp + "1" + (d + List(6) { (chk shr (5 * (5 - it))) and 31 }).map { CHARSET[it] }.joinToString("")
    }

    fun decode(s: String): Pair<String, ByteArray>? {
        val l = s.lowercase()
        val pos = l.lastIndexOf('1')
        if (pos < 1 || l.length - pos < 7) return null
        val data = l.substring(pos + 1).map { CHARSET.indexOf(it).takeIf { i -> i >= 0 } ?: return null }
        val hrp = l.substring(0, pos)
        if (polymod(hrpExpand(hrp) + data) != 1) return null
        val bytes = convert(data.dropLast(6), 5, 8, false) ?: return null
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    /** hex or npub1… -> 32-byte key */
    fun pubkey(s: String): ByteArray? {
        val t = s.trim()
        if (t.startsWith("npub1")) return decode(t)?.second?.takeIf { it.size == 32 }
        return t.lowercase().unhex()?.takeIf { it.size == 32 }
    }

    fun npub(pk: ByteArray) = encode("npub", pk)
}

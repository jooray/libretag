package app.libretag.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/**
 * NIP-59 gift wrap and NIP-17 private DMs: an unsigned kind 14 rumor, sealed (kind 13, NIP-44,
 * signed by the sender), wrapped (kind 1059, NIP-44, signed by a fresh random key, one `p` tag).
 * The seal and the wrap get independent random times up to two days in the past.
 */
object Nip59 {
    const val KIND_SEAL = 13
    const val KIND_WRAP = 1059
    const val TWO_DAYS_S = 2 * 86400L

    /** The rumor as JSON (id included, no sig), as it goes inside the seal. */
    fun rumorJson(r: NostrEvent): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", r.id.ifEmpty { r.computeId() })
        put("pubkey", r.pubkey)
        put("created_at", r.createdAt)
        put("kind", r.kind)
        put("tags", buildJsonArray { r.tags.forEach { t -> add(buildJsonArray { t.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) } })
        put("content", r.content)
    })

    fun randomPast(now: Long, random: SecureRandom): Long = now - (random.nextLong() and Long.MAX_VALUE) % TWO_DAYS_S

    fun seal(senderSk: ByteArray, recipient: ByteArray, rumor: NostrEvent, createdAt: Long, extraTags: List<List<String>> = emptyList(),
             nonce: ByteArray? = null): NostrEvent {
        val ck = Nip44.conversationKey(senderSk, recipient)
        val ct = if (nonce != null) Nip44.encrypt(rumorJson(rumor), ck, nonce) else Nip44.encrypt(rumorJson(rumor), ck)
        return NostrEvent.sign(senderSk, createdAt, KIND_SEAL, extraTags, ct)
    }

    fun wrap(wrapSk: ByteArray, recipient: ByteArray, seal: NostrEvent, createdAt: Long, extraTags: List<List<String>> = emptyList()): NostrEvent {
        val ck = Nip44.conversationKey(wrapSk, recipient)
        val ct = Nip44.encrypt(Json.encodeToString(JsonObject.serializer(), seal.toJson()), ck)
        return NostrEvent.sign(wrapSk, createdAt, KIND_WRAP, listOf(listOf("p", recipient.hex())) + extraTags, ct)
    }

    /** What the recipient gets back from a wrap: the seal and the rumor, or null if anything fails. */
    data class Opened(val seal: NostrEvent, val rumor: NostrEvent)

    fun unwrap(recipientSk: ByteArray, wrap: NostrEvent): Opened? = runCatching {
        if (wrap.kind != KIND_WRAP || !wrap.verify()) return null
        val sealJson = Nip44.decrypt(wrap.content, Nip44.conversationKey(recipientSk, wrap.pubkey.unhex()!!))
        val seal = NostrEvent.fromJson(Json.parseToJsonElement(sealJson) as JsonObject) ?: return null
        if (seal.kind != KIND_SEAL || !seal.verify()) return null
        val rumorJson = Nip44.decrypt(seal.content, Nip44.conversationKey(recipientSk, seal.pubkey.unhex()!!))
        val rumor = NostrEvent.fromJson(Json.parseToJsonElement(rumorJson) as JsonObject) ?: return null
        // NIP-17: the seal's author must be the rumor's author, else anyone could impersonate anyone
        if (rumor.pubkey != seal.pubkey || (rumor.id.isNotEmpty() && rumor.id != rumor.computeId())) return null
        Opened(seal, rumor)
    }.getOrNull()
}

object Nip17 {
    const val KIND_DM = 14

    /** A gift-wrapped DM and the wrap's one-time key (used for NIP-42 AUTH, so relays don't learn the sender). */
    data class Wrapped(val wrap: NostrEvent, val wrapSk: ByteArray, val rumor: NostrEvent)

    /**
     * A NIP-17 DM from [senderSk] to [recipient] (x-only). With [expiresAt] (unix s) the rumor, the
     * seal and the wrap carry ["expiration"], so relays drop it (NIP-40, NIP-17 "disappearing").
     */
    fun dm(senderSk: ByteArray, recipient: ByteArray, text: String, now: Long, expiresAt: Long? = null,
           random: SecureRandom = SecureRandom()): Wrapped {
        val exp = expiresAt?.let { listOf(listOf("expiration", it.toString())) } ?: emptyList()
        val rumor = NostrEvent(NostrEvent.xonly(senderSk).hex(), now, KIND_DM, listOf(listOf("p", recipient.hex())) + exp, text).withId()
        val seal = Nip59.seal(senderSk, recipient, rumor, Nip59.randomPast(now, random), exp)
        var wrapSk: ByteArray
        do { wrapSk = ByteArray(32).also { random.nextBytes(it) } } while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(wrapSk))
        val wrap = Nip59.wrap(wrapSk, recipient, seal, Nip59.randomPast(now, random), exp)
        return Wrapped(wrap, wrapSk, rumor)
    }
}

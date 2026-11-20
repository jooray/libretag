package app.libretag.backup

import app.libretag.nostr.Nip44
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import app.libretag.nostr.isLowerHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.security.SecureRandom

/** Why the owner's signer gave no answer. */
class SignerException(val reason: Reason, msg: String = reason.name) : Exception(msg) {
    enum class Reason {
        /** the user said no (or a remembered "no") */
        REJECTED,
        /** it would need the user, and no screen is open to ask (background save) */
        NEEDS_USER,
        /** no answer: the signer app is gone, the bunker is offline, or it timed out */
        UNAVAILABLE,
        /** it answered with something we can't use (wrong event, broken signature) */
        BAD_ANSWER,
    }
}

/**
 * The owner's signer as the backup needs it (NIP-55 Amber, NIP-46 bunker or nostrconnect). All
 * three calls throw [SignerException] when there is no answer. The backup copy is encrypted to the
 * owner's own key, so `peer` is always [pubkeyHex].
 */
interface OwnerSigner {
    val pubkeyHex: String
    suspend fun nip44Encrypt(plaintext: String, peerHex: String): String
    suspend fun nip44Decrypt(payload: String, peerHex: String): String
    /** Signs [event] (pubkey, created_at, kind, tags, content set; id computed). Returns it signed. */
    suspend fun signEvent(event: NostrEvent): NostrEvent
}

/**
 * The backup copy for the owner's npub (SPEC.md 12.2 B): the 12.1 plaintext, NIP-44 v2 encrypted by
 * the owner's signer to the owner's own key, in a kind 30078 event signed by the owner with only a
 * random `d` tag. No `alt` tag and nothing else that names LibreTag. The `d` is also inside the
 * plaintext, which is how a reader tells our event from other apps' app data.
 */
object NpubCopy {
    const val KIND = 30078
    const val DELETION_KIND = 5
    /** how many candidate events we ask the signer to decrypt while looking for the backup */
    const val MAX_CANDIDATES = 10

    sealed class Found {
        data class Backup(val payload: BackupPayload, val event: NostrEvent) : Found()
        /** the newest event at our `d` is empty: the backup was deleted */
        object Deleted : Found()
        object None : Found()
    }

    fun newD(random: SecureRandom = SecureRandom()): String = ByteArray(16).also { random.nextBytes(it) }.hex()

    fun dTag(ev: NostrEvent): String? = ev.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    /** All of the owner's app data (we can't ask for our `d` on a new device: we don't know it). */
    fun discoveryFilter(ownerHex: String): JsonObject = buildJsonObject {
        put("kinds", buildJsonArray { add(JsonPrimitive(KIND)) })
        put("authors", buildJsonArray { add(JsonPrimitive(ownerHex)) })
    }

    /** Our own copy, once the `d` is known. */
    fun filter(ownerHex: String, d: String): JsonObject = buildJsonObject {
        put("kinds", buildJsonArray { add(JsonPrimitive(KIND)) })
        put("authors", buildJsonArray { add(JsonPrimitive(ownerHex)) })
        put("#d", buildJsonArray { add(JsonPrimitive(d)) })
    }

    fun address(ownerHex: String, d: String) = "$KIND:$ownerHex:$d"

    private fun valid(ownerHex: String, ev: NostrEvent) = ev.kind == KIND && ev.pubkey == ownerHex && dTag(ev) != null && ev.verify()

    /**
     * Newest event per `d` (ties: lowest id), only the owner's, signed, with a NIP-44 v2 payload;
     * a `d` whose newest event is empty is left out (deleted). Newest first, at most [MAX_CANDIDATES].
     */
    fun candidates(ownerHex: String, events: List<NostrEvent>): List<NostrEvent> =
        events.filter { valid(ownerHex, it) }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            .distinctBy { dTag(it) }
            .filter { Nip44.isPayload(it.content) }
            .take(MAX_CANDIDATES)

    /** Decrypts one event; the payload only if it is a v1 backup whose `d` is the event's. */
    suspend fun open(signer: OwnerSigner, ev: NostrEvent): BackupPayload? {
        val text = try {
            signer.nip44Decrypt(ev.content, signer.pubkeyHex)
        } catch (e: SignerException) {
            if (e.reason == SignerException.Reason.BAD_ANSWER) return null else throw e
        }
        val p = KeyBackup.parse(text) ?: return null
        return p.takeIf { it.d != null && it.d == dTag(ev) }
    }

    /**
     * On a new device: the first candidate (newest first) that decrypts to our backup. Signer
     * errors other than a bad answer stop the search (the user said no, or it's offline).
     */
    suspend fun discover(signer: OwnerSigner, events: List<NostrEvent>): Found {
        for (ev in candidates(signer.pubkeyHex, events)) {
            open(signer, ev)?.let { return Found.Backup(it, ev) }
        }
        return Found.None
    }

    /** With a known `d`: the newest event there, deleted or not. */
    suspend fun read(signer: OwnerSigner, d: String, events: List<NostrEvent>): Found {
        val newest = events.filter { valid(signer.pubkeyHex, it) && dTag(it) == d }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            .firstOrNull() ?: return Found.None
        if (newest.content.isEmpty()) return Found.Deleted
        return open(signer, newest)?.let { Found.Backup(it, newest) } ?: Found.None
    }

    /** Encrypts [payload] (its `d` must be set) and has the owner sign the event. */
    suspend fun seal(signer: OwnerSigner, payload: BackupPayload, now: Long = System.currentTimeMillis() / 1000): NostrEvent {
        val d = requireNotNull(payload.d) { "payload.d" }
        require(isLowerHex(d, 16))
        val content = signer.nip44Encrypt(KeyBackup.plaintext(payload), signer.pubkeyHex)
        if (!Nip44.isPayload(content)) throw SignerException(SignerException.Reason.BAD_ANSWER, "not a NIP-44 payload")
        return sign(signer, NostrEvent(signer.pubkeyHex, now, KIND, listOf(listOf("d", d)), content).withId())
    }

    /** An empty replacement and a NIP-09 request for the address, both signed by the owner. */
    suspend fun deletion(signer: OwnerSigner, d: String, now: Long = System.currentTimeMillis() / 1000): List<NostrEvent> = listOf(
        sign(signer, NostrEvent(signer.pubkeyHex, now, KIND, listOf(listOf("d", d)), "").withId()),
        sign(signer, NostrEvent(signer.pubkeyHex, now, DELETION_KIND,
            listOf(listOf("a", address(signer.pubkeyHex, d)), listOf("k", KIND.toString())), "").withId()),
    )

    private suspend fun sign(signer: OwnerSigner, ev: NostrEvent): NostrEvent {
        val s = signer.signEvent(ev)
        if (s.id != ev.id || s.pubkey != ev.pubkey || !s.verify()) throw SignerException(SignerException.Reason.BAD_ANSWER, "signed event does not match")
        return s
    }
}

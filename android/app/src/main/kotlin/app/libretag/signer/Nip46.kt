package app.libretag.signer

import android.net.Uri
import app.libretag.backup.OwnerSigner
import app.libretag.backup.SignerException
import app.libretag.backup.SignerException.Reason
import app.libretag.nostr.Nip44
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import app.libretag.nostr.isLowerHex
import app.libretag.nostr.unhex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NIP-46 remote signing, done by the app itself (LibreTag has no server): kind 24133 requests,
 * NIP-44 encrypted between a throwaway client key and the remote signer, over the signer's relays.
 *
 * - `bunker://<signer pubkey>?relay=…&secret=…`: we send `connect` with the secret, then ask
 *   `get_public_key` for the owner's key.
 * - `nostrconnect://<client pubkey>?relay=…&secret=…&perms=…`: shown as a QR or opened in a signer
 *   on this phone; the signer answers our secret, which tells us its pubkey. Only an answer with
 *   exactly our secret counts.
 * - An `auth_url` answer is handed to [onAuthUrl] (some signers want a browser approval first);
 *   the real answer to the same request id comes later.
 */
object Nip46 {
    const val KIND = 24133
    /** what we ask a signer to allow once, so saves don't prompt per event */
    const val PERMS = "nip44_encrypt,nip44_decrypt,sign_event:30078,sign_event:5"

    data class Bunker(val signerPubkey: String, val relays: List<String>, val secret: String?)

    /** Parses a pasted bunker:// link; null if it isn't one (checked before any network call). */
    fun parseBunker(raw: String): Bunker? {
        val t = raw.trim()
        if (!t.startsWith("bunker://", ignoreCase = true)) return null
        val u = runCatching { Uri.parse(t) }.getOrNull() ?: return null
        val pk = u.host?.lowercase()?.takeIf { isLowerHex(it, 32) } ?: return null
        val relays = u.getQueryParameters("relay").map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
        if (relays.isEmpty()) return null
        return Bunker(pk, relays, u.getQueryParameter("secret")?.takeIf { it.isNotBlank() })
    }

    fun nostrConnectUri(clientPubkey: String, relays: List<String>, secret: String, name: String): String =
        "nostrconnect://$clientPubkey?" + (relays.map { "relay=" + Uri.encode(it) } +
            listOf("secret=$secret", "perms=" + Uri.encode(PERMS), "name=" + Uri.encode(name),
                "url=" + Uri.encode("https://cypherpunk.today/libretag/"), "image=" + Uri.encode(ICON_URL))).joinToString("&")

    /** shown by signers (Amber) next to the name; the same icon the web app serves */
    const val ICON_URL = "https://cypherpunk.today/libretag/app/icon-512.png"

    fun newSecret(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()

    fun newClientKey(): ByteArray {
        val r = SecureRandom()
        while (true) {
            val k = ByteArray(32).also { r.nextBytes(it) }
            if (runCatching { fr.acinq.secp256k1.Secp256k1.secKeyVerify(k) }.getOrDefault(false)) return k
        }
    }

    /** Request content (NIP-46): {"id","method","params"} with string params. */
    fun requestJson(id: String, method: String, params: List<String>): String = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("method", JsonPrimitive(method))
        put("params", buildJsonArray { params.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    data class Reply(val id: String, val result: String?, val error: String?)

    fun parseReply(text: String): Reply? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        return Reply(id, (o["result"] as? JsonPrimitive)?.contentOrNull, (o["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() })
    }
}

/**
 * The relay side of a NIP-46 session: listens for kind 24133 events to our client key and
 * sends requests. One per signer session; [close] when done.
 */
class Nip46Link(
    private val http: OkHttpClient,
    private val clientSk: ByteArray,
    private val relays: List<String>,
    private val onAuthUrl: (String) -> Unit = {},
) {
    val clientPubkey: String = NostrEvent.xonly(clientSk).hex()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Nip46.Reply>>()
    @Volatile private var connectWait: Pair<String, CompletableDeferred<String>>? = null
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val opened = CompletableDeferred<Unit>()

    fun open() {
        val since = System.currentTimeMillis() / 1000 - 60
        val req = buildJsonArray {
            add(JsonPrimitive("REQ")); add(JsonPrimitive("lt46"))
            add(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(Nip46.KIND)) })
                put("#p", buildJsonArray { add(JsonPrimitive(clientPubkey)) })
                put("since", JsonPrimitive(since))
            })
        }.toString()
        for (url in relays) {
            if (sockets.containsKey(url)) continue
            val ws = runCatching {
                http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(req)
                        opened.complete(Unit)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sockets.remove(url) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { sockets.remove(url) }
                })
            }.getOrNull() ?: continue
            sockets[url] = ws
        }
    }

    fun close() {
        sockets.values.forEach { runCatching { it.close(1000, null) } }
        sockets.clear()
        pending.values.forEach { it.completeExceptionally(SignerException(Reason.UNAVAILABLE, "closed")) }
        pending.clear()
    }

    private fun handle(text: String) {
        val a = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
        if (a.getOrNull(0)?.jsonPrimitive?.contentOrNull != "EVENT") return
        val ev = (a.getOrNull(2) as? JsonObject)?.let { NostrEvent.fromJson(it) } ?: return
        if (ev.kind != Nip46.KIND || !seen.add(ev.id) || !ev.verify()) return
        if (ev.tags.none { it.size >= 2 && it[0] == "p" && it[1] == clientPubkey }) return
        val plain = try {
            Nip44.decrypt(ev.content, Nip44.conversationKey(clientSk, ev.pubkey.unhex()!!))
        } catch (e: Exception) {
            return
        }
        val reply = Nip46.parseReply(plain) ?: return
        connectWait?.let { (secret, done) ->
            if (reply.result == secret) { done.complete(ev.pubkey); return }
        }
        if (reply.result == "auth_url" && reply.error != null) { onAuthUrl(reply.error); return }
        pending[reply.id]?.complete(reply)
    }

    /** nostrconnect: waits until a signer answers with exactly [secret]; returns its pubkey. */
    suspend fun awaitConnect(secret: String, timeoutMs: Long): String? {
        val done = CompletableDeferred<String>()
        connectWait = secret to done
        open()
        return withTimeoutOrNull(timeoutMs) { done.await() }.also { connectWait = null }
    }

    suspend fun request(signerPubkey: String, method: String, params: List<String>, timeoutMs: Long): Nip46.Reply {
        open()
        withTimeoutOrNull(10_000) { opened.await() } ?: throw SignerException(Reason.UNAVAILABLE, "no relay")
        val id = Nip46.newSecret()
        val done = CompletableDeferred<Nip46.Reply>()
        pending[id] = done
        try {
            val content = Nip44.encrypt(Nip46.requestJson(id, method, params), Nip44.conversationKey(clientSk, signerPubkey.unhex()!!))
            val ev = NostrEvent.sign(clientSk, System.currentTimeMillis() / 1000, Nip46.KIND, listOf(listOf("p", signerPubkey)), content)
            val msg = buildJsonArray { add(JsonPrimitive("EVENT")); add(ev.toJson()) }.toString()
            if (sockets.values.count { it.send(msg) } == 0) throw SignerException(Reason.UNAVAILABLE, "no relay")
            return withTimeoutOrNull(timeoutMs) { done.await() } ?: throw SignerException(Reason.UNAVAILABLE, "timeout")
        } finally {
            pending.remove(id)
        }
    }
}

/** The owner's signer over NIP-46. [pubkeyHex] is the owner (from get_public_key). */
class Nip46Signer(
    private val link: Nip46Link,
    val signerPubkey: String,
    override val pubkeyHex: String,
    private val timeoutMs: Long = 90_000,
) : OwnerSigner {
    override suspend fun nip44Encrypt(plaintext: String, peerHex: String): String {
        val r = link.request(signerPubkey, "nip44_encrypt", listOf(peerHex, plaintext), timeoutMs)
        return r.result?.takeIf { r.error == null } ?: throw SignerException(Reason.REJECTED, r.error ?: "no result")
    }

    override suspend fun nip44Decrypt(payload: String, peerHex: String): String {
        val r = link.request(signerPubkey, "nip44_decrypt", listOf(peerHex, payload), timeoutMs)
        // an error here is usually "can't decrypt" (another app's data): not a refusal
        return r.result?.takeIf { r.error == null } ?: throw SignerException(Reason.BAD_ANSWER, r.error ?: "no result")
    }

    override suspend fun signEvent(event: NostrEvent): NostrEvent {
        val unsigned = buildJsonObject {
            put("kind", JsonPrimitive(event.kind))
            put("content", JsonPrimitive(event.content))
            put("tags", buildJsonArray { event.tags.forEach { t -> add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } }) } })
            put("created_at", JsonPrimitive(event.createdAt))
            put("pubkey", JsonPrimitive(event.pubkey))
        }.toString()
        val r = link.request(signerPubkey, "sign_event", listOf(unsigned), timeoutMs)
        val raw = r.result?.takeIf { r.error == null } ?: throw SignerException(Reason.REJECTED, r.error ?: "no result")
        val signed = runCatching { NostrEvent.fromJson(Json.parseToJsonElement(raw) as JsonObject) }.getOrNull()
            ?: throw SignerException(Reason.BAD_ANSWER)
        val out = event.copy(sig = signed.sig.lowercase())
        if (signed.pubkey != event.pubkey || signed.id != event.id || !out.verify()) throw SignerException(Reason.BAD_ANSWER, "signed event does not match")
        return out
    }

    companion object {
        /** bunker://: connect with the secret, then get_public_key. Returns the signer for the owner. */
        suspend fun connectBunker(link: Nip46Link, b: Nip46.Bunker, timeoutMs: Long = 120_000): Nip46Signer {
            val c = link.request(b.signerPubkey, "connect", listOfNotNull(b.signerPubkey, b.secret ?: "", Nip46.PERMS), timeoutMs)
            if (c.error != null) throw SignerException(Reason.REJECTED, c.error)
            return withOwner(link, b.signerPubkey, timeoutMs)
        }

        /** After a nostrconnect answer from [signerPubkey]: ask who the owner is. */
        suspend fun withOwner(link: Nip46Link, signerPubkey: String, timeoutMs: Long = 120_000): Nip46Signer {
            val r = link.request(signerPubkey, "get_public_key", emptyList(), timeoutMs)
            val pk = r.result?.trim()?.lowercase()?.takeIf { r.error == null && isLowerHex(it, 32) }
                ?: throw SignerException(Reason.BAD_ANSWER, r.error ?: "no public key")
            return Nip46Signer(link, signerPubkey, pk)
        }
    }
}

/** A shared client for NIP-46 sockets (read timeout off: replies can take minutes). */
val nip46Http: OkHttpClient by lazy {
    OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
}

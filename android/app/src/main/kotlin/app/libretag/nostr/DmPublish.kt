package app.libretag.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** What one relay said to an EVENT: accepted, or why not ("timeout", "unreachable", the relay's own reason). */
data class RelayAck(val url: String, val ok: Boolean, val reason: String = "") {
    val host: String get() = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
}

/** NIP-42: the AUTH event (kind 22242) for [url]'s [challenge], signed by the key that publishes. */
object Nip42 {
    const val KIND_AUTH = 22242

    fun authEvent(sk: ByteArray, url: String, challenge: String, now: Long): NostrEvent =
        NostrEvent.sign(sk, now, KIND_AUTH, listOf(listOf("relay", url), listOf("challenge", challenge)), "")
}

/**
 * One EVENT on one connection, with NIP-42 AUTH: when the relay sends a challenge we answer it
 * with [authSk] (the tag's dm key, never the owner's), and an `auth-required:` refusal is retried
 * once after the relay accepted our AUTH. No network here: [send] writes a frame, [onMessage]
 * returns the final [RelayAck] once there is one.
 */
class PublishSession(
    private val url: String,
    private val event: NostrEvent,
    private val authSk: ByteArray?,
    private val send: (String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val eventMsg = buildJsonArray { add(JsonPrimitive("EVENT")); add(event.toJson()) }.toString()
    private var authId: String? = null
    private var authOk = false
    private var waitingForAuth = false
    private var retried = false
    var lastReason = ""
        private set

    fun onOpen() = send(eventMsg)

    fun onMessage(text: String): RelayAck? {
        val a = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        when (a.getOrNull(0)?.jsonPrimitive?.contentOrNull) {
            "AUTH" -> {
                val challenge = a.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return null
                val sk = authSk ?: return null
                val ev = Nip42.authEvent(sk, url, challenge, now())
                authId = ev.id
                send(buildJsonArray { add(JsonPrimitive("AUTH")); add(ev.toJson()) }.toString())
            }
            "OK" -> {
                val id = a.getOrNull(1)?.jsonPrimitive?.contentOrNull
                val ok = a.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                val why = a.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: ""
                if (id != null && id == authId) {
                    if (!ok) return RelayAck(url, false, why.ifEmpty { "auth-required: AUTH refused" })
                    authOk = true
                    if (waitingForAuth) { waitingForAuth = false; retried = true; send(eventMsg) }
                    return null
                }
                if (id != event.id) return null
                if (ok || why.startsWith("duplicate:")) return RelayAck(url, true, why)
                lastReason = why
                if (why.startsWith("auth-required:") && authSk != null && !retried) {
                    if (authOk) { retried = true; send(eventMsg) } else waitingForAuth = true
                    return null
                }
                return RelayAck(url, false, why)
            }
            "CLOSED", "NOTICE" -> lastReason = a.getOrNull(a.size - 1)?.jsonPrimitive?.contentOrNull ?: lastReason
        }
        return null
    }
}

/** Short connections that publish one event and report each relay's answer (DMs, the tag's profile). */
object DmPublish {
    const val TIMEOUT = "timeout"
    const val UNREACHABLE = "unreachable"
    private val http by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    suspend fun publish(urls: List<String>, event: NostrEvent, authSk: ByteArray?, timeoutMs: Long = 12_000): List<RelayAck> = coroutineScope {
        urls.distinct().map { url ->
            async {
                val done = CompletableDeferred<RelayAck>()
                var session: PublishSession? = null
                val ws = runCatching {
                    http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            session = PublishSession(url, event, authSk, { webSocket.send(it) }).also { it.onOpen() }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            synchronized(this) { session?.onMessage(text) }?.let { done.complete(it) }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            done.complete(RelayAck(url, false, session?.lastReason?.ifEmpty { null } ?: UNREACHABLE))
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            done.complete(RelayAck(url, false, session?.lastReason?.ifEmpty { null } ?: UNREACHABLE))
                        }
                    })
                }.getOrNull() ?: return@async RelayAck(url, false, UNREACHABLE)
                val r = withTimeoutOrNull(timeoutMs) { done.await() }
                    ?: RelayAck(url, false, session?.lastReason?.ifEmpty { null } ?: TIMEOUT)
                runCatching { ws.close(1000, null) }
                r
            }
        }.awaitAll()
    }
}

/** The tag's public profile (kind 0): a recognisable sender, nothing about the tag itself. */
object TagProfile {
    const val PICTURE = "https://cypherpunk.today/libretag/app/icon-512.png"
    const val CONTENT = """{"name":"LibreTag","about":"Messages from your LibreTag tag (battery alerts).","picture":"$PICTURE"}"""

    fun event(sk: ByteArray, now: Long): NostrEvent = NostrEvent.sign(sk, now, 0, emptyList(), CONTENT)
}

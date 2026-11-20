package app.libretag.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/**
 * Short connections to a given set of relays (the owner's write relays are not in the pool):
 * one REQ until EOSE, or one EVENT until OK. Used by the key backup.
 */
object RelayOnce {
    private val http by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    /** [answered]: relays that sent EOSE. With 0 the caller can't tell "nothing there" from "offline". */
    data class Result(val events: List<NostrEvent>, val answered: Int)

    suspend fun query(urls: List<String>, filters: List<JsonObject>, timeoutMs: Long = 8_000): Result = coroutineScope {
        val sub = "b" + System.nanoTime().toString(36)
        val req = buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(sub)); filters.forEach { add(it) } }.toString()
        val per = urls.distinct().map { url ->
            async {
                val got = java.util.Collections.synchronizedList(mutableListOf<NostrEvent>())
                val eose = CompletableDeferred<Boolean>()
                val ws = runCatching {
                    http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(req) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val a = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
                            when (a.getOrNull(0)?.jsonPrimitive?.contentOrNull) {
                                "EVENT" -> (a.getOrNull(2) as? JsonObject)?.let { NostrEvent.fromJson(it) }?.let { got += it }
                                "EOSE" -> eose.complete(true)
                                "CLOSED" -> eose.complete(false)
                            }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { eose.complete(false) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { eose.complete(false) }
                    })
                }.getOrNull() ?: return@async emptyList<NostrEvent>() to false
                val ok = withTimeoutOrNull(timeoutMs) { eose.await() } ?: false
                runCatching { ws.close(1000, null) }
                got.toList() to ok
            }
        }.awaitAll()
        Result(per.flatMap { it.first }.distinctBy { it.id }, per.count { it.second })
    }

    /** How many relays answered OK (or "duplicate:"). */
    suspend fun publish(urls: List<String>, event: NostrEvent, timeoutMs: Long = 10_000): Int = coroutineScope {
        val msg = buildJsonArray { add(JsonPrimitive("EVENT")); add(event.toJson()) }.toString()
        urls.distinct().map { url ->
            async {
                val done = CompletableDeferred<Boolean>()
                val ws = runCatching {
                    http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(msg) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val a = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
                            if (a.getOrNull(0)?.jsonPrimitive?.contentOrNull == "OK" && a.getOrNull(1)?.jsonPrimitive?.contentOrNull == event.id) {
                                val ok = a.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                                done.complete(ok || (a.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: "").startsWith("duplicate:"))
                            }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(false) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(false) }
                    })
                }.getOrNull() ?: return@async false
                val r = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
                runCatching { ws.close(1000, null) }
                r
            }
        }.awaitAll().count { it }
    }

    /** NIP-65 write relays: `r` tags without a marker or marked `write`, newest kind 10002 of [pubkeyHex]. */
    fun writeRelays(events: List<NostrEvent>, pubkeyHex: String): List<String> =
        events.filter { it.kind == 10002 && it.pubkey == pubkeyHex && it.verify() }.maxByOrNull { it.createdAt }?.tags.orEmpty()
            .filter { it.size >= 2 && it[0] == "r" && (it.size == 2 || it[2].isBlank() || it[2] == "write") }
            .mapNotNull { InboxRelays.normalize(it[1]) }.filter { InboxRelays.isPublicWss(it) }.distinct()
}

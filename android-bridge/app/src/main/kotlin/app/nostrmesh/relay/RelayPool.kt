package app.nostrmesh.relay

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** NIP-01 relay pool: publish with OK handling and retries, subscriptions that survive reconnects. */
class RelayPool(
    private val scope: CoroutineScope,
    private val onEvent: suspend (JsonObject, String) -> Unit,
) {
    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val relays = ConcurrentHashMap<String, Relay>()
    private val _connected = MutableStateFlow(0 to 0)
    /** (connected, configured) */
    val connected: StateFlow<Pair<Int, Int>> = _connected

    private val subs = ConcurrentHashMap<String, List<JsonObject>>()
    var publishUrls: List<String> = emptyList()
        private set

    fun setRelays(publish: List<String>, subscribe: List<String>) {
        publishUrls = publish
        val want = (publish + subscribe).toSet()
        relays.keys.filter { it !in want }.forEach { relays.remove(it)?.close() }
        want.filter { !relays.containsKey(it) }.forEach { url -> relays[url] = Relay(url, url in subscribe).also { it.start() } }
        relays.values.forEach { it.subscriber = it.url in subscribe }
        emit()
    }

    fun stop() {
        relays.values.forEach { it.close() }
        relays.clear()
        emit()
    }

    private fun emit() {
        _connected.value = relays.values.count { it.isConnected } to relays.size
    }

    fun subscribe(subId: String, filters: List<JsonObject>) {
        subs[subId] = filters
        relays.values.filter { it.subscriber }.forEach { it.sendReq(subId, filters) }
    }

    fun unsubscribe(subId: String) {
        if (subs.remove(subId) == null) return
        relays.values.filter { it.subscriber }.forEach { it.sendClose(subId) }
    }

    /**
     * One-shot REQ on every live subscribe relay: events until EOSE (or [timeoutMs]), then CLOSE;
     * deduplicated by id.
     */
    suspend fun query(filters: List<JsonObject>, timeoutMs: Long = 6_000): List<JsonObject> {
        val sid = "nm-q" + java.lang.Long.toHexString(rng.nextLong())
        val res = relays.values.filter { it.subscriber && it.isConnected }.map { r ->
            scope.async { r.query(sid, filters, timeoutMs) }
        }.awaitAll()
        val seen = HashSet<String>()
        return res.flatten().filter { (it["id"]?.jsonPrimitive?.contentOrNull ?: "").let(seen::add) }
    }

    /**
     * Publishes to relays outside the pool (a recipient's inbox relays) over a short-lived
     * connection each, no retry. True when at least one accepted it.
     */
    suspend fun publishOnce(event: JsonObject, urls: List<String>, timeoutMs: Long = 15_000): Boolean {
        val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return false
        return urls.map { url ->
            scope.async {
                val ok = CompletableDeferred<Boolean>()
                val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(event) }.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
                        if (msg.getOrNull(0)?.jsonPrimitive?.contentOrNull != "OK") return
                        if (msg.getOrNull(1)?.jsonPrimitive?.contentOrNull != id) return
                        val accepted = msg.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                        val why = msg.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: ""
                        ok.complete(accepted || why.startsWith("duplicate:"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        ok.complete(false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        ok.complete(false)
                    }
                })
                val r = withTimeoutOrNull(timeoutMs) { ok.await() } ?: false
                ws.close(1000, null)
                if (!r) Log.i(TAG, "$url: inbox publish failed")
                r
            }
        }.awaitAll().any { it }
    }

    /** True when at least one relay accepted the event (`duplicate:` counts as accepted). */
    suspend fun publish(event: JsonObject): Boolean {
        val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return false
        val results = publishUrls.mapNotNull { relays[it] }.map { r ->
            scope.async {
                for (wait in RETRY_DELAYS_MS) {
                    if (wait > 0) delay(wait)
                    val (ok, msg) = r.publish(id, event)
                    if (ok || msg.startsWith("duplicate:")) return@async true
                    if (FINAL.any { msg.startsWith(it) }) return@async false
                }
                false
            }
        }.awaitAll()
        return results.any { it }
    }

    private inner class Relay(val url: String, var subscriber: Boolean) {
        @Volatile var ws: WebSocket? = null
        @Volatile var isConnected = false
        @Volatile private var closed = false
        private var backoff = 2_000L
        private val oks = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
        private val queries = ConcurrentHashMap<String, Pair<MutableList<JsonObject>, CompletableDeferred<Unit>>>()
        private var job: Job? = null

        fun start() = connect()

        private fun connect() {
            if (closed) return
            ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    backoff = 2_000L
                    emit()
                    if (subscriber) subs.forEach { (id, f) -> sendReq(id, f) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped("${t.message}")
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped("closed $code")
            })
        }

        private fun dropped(why: String) {
            isConnected = false
            ws = null
            emit()
            oks.values.forEach { it.complete(false to "error: $why") }
            oks.clear()
            queries.values.forEach { it.second.complete(Unit) }
            if (closed) return
            Log.w(TAG, "$url: $why, retry in ${backoff / 1000} s")
            job = scope.launch {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(120_000L)
                connect()
            }
        }

        fun close() {
            closed = true
            job?.cancel()
            ws?.close(1000, null)
        }

        fun sendReq(subId: String, filters: List<JsonObject>) {
            ws?.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(subId)); filters.forEach { add(it) } }.toString())
        }

        fun sendClose(subId: String) {
            ws?.send(buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(subId)) }.toString())
        }

        suspend fun query(sid: String, filters: List<JsonObject>, timeoutMs: Long): List<JsonObject> {
            val socket = ws ?: return emptyList()
            val got = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
            val done = CompletableDeferred<Unit>()
            queries[sid] = got to done
            try {
                sendReq(sid, filters)
                withTimeoutOrNull(timeoutMs) { done.await() }
            } finally {
                queries.remove(sid)
                socket.send(buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(sid)) }.toString())
            }
            return synchronized(got) { got.toList() }
        }

        suspend fun publish(id: String, event: JsonObject): Pair<Boolean, String> {
            val socket = ws ?: return false to "error: not connected"
            val d = CompletableDeferred<Pair<Boolean, String>>()
            oks[id] = d
            socket.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(event) }.toString())
            return withTimeoutOrNull(15_000) { d.await() } ?: (false to "error: timeout").also { oks.remove(id) }
        }

        private fun handle(text: String) {
            val msg = try {
                Json.parseToJsonElement(text) as? JsonArray
            } catch (e: Exception) {
                null
            } ?: return
            when (msg.getOrNull(0)?.jsonPrimitive?.contentOrNull) {
                "OK" -> {
                    val id = msg.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return
                    val ok = msg.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                    oks.remove(id)?.complete(ok to (msg.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: ""))
                }
                "EVENT" -> {
                    val ev = (msg.getOrNull(2) as? JsonObject) ?: return
                    val q = msg.getOrNull(1)?.jsonPrimitive?.contentOrNull?.let { queries[it] }
                    if (q != null) {
                        if (q.first.size < 64) q.first.add(ev)
                        return
                    }
                    scope.launch { onEvent(ev, url) }
                }
                "EOSE" -> msg.getOrNull(1)?.jsonPrimitive?.contentOrNull?.let { queries[it] }?.second?.complete(Unit)
                "CLOSED" -> {
                    msg.getOrNull(1)?.jsonPrimitive?.contentOrNull?.let { queries[it] }?.second?.complete(Unit)
                    Log.i(TAG, "$url: $text")
                }
                "NOTICE" -> Log.i(TAG, "$url: $text")
            }
        }
    }

    companion object {
        const val TAG = "RelayPool"
        val RETRY_DELAYS_MS = longArrayOf(0, 5_000, 30_000, 120_000)
        private val rng = java.security.SecureRandom()
        val FINAL = listOf("invalid:", "pow:", "blocked:", "restricted:", "auth-required:")
    }
}

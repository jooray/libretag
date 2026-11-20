package app.libretag.nostr

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

    /** Live state of each relay, for the Connections screen. */
    data class RelayStatus(
        val url: String,
        val connected: Boolean,
        val since: Long = 0,
        val lastError: String? = null,
        val lastErrorAt: Long = 0,
        val lastEventAt: Long = 0,
        /** round trip of the last publish (ms) */
        val rttMs: Long? = null,
    )
    private val _status = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val status: StateFlow<Map<String, RelayStatus>> = _status
    private fun setStatus(url: String, f: (RelayStatus) -> RelayStatus) {
        synchronized(_status) {
            val cur = _status.value[url] ?: RelayStatus(url, false)
            _status.value = _status.value + (url to f(cur))
        }
    }

    /** One-off queries (backup restore): sub id -> collector, finished on EOSE from every relay. */
    private val queries = ConcurrentHashMap<String, Pair<MutableList<JsonObject>, CompletableDeferred<Unit>>>()
    private val eoses = ConcurrentHashMap<String, MutableSet<String>>()

    /** Events matching [filters] from the connected relays, waiting for EOSE (or [timeoutMs]). */
    suspend fun query(filters: List<JsonObject>, timeoutMs: Long = 8_000): List<JsonObject> {
        val id = "q" + System.nanoTime().toString(36)
        val got = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val done = CompletableDeferred<Unit>()
        queries[id] = got to done
        eoses[id] = java.util.Collections.synchronizedSet(mutableSetOf())
        val targets = relays.values.filter { it.isConnected }
        if (targets.isEmpty()) { queries.remove(id); eoses.remove(id); return emptyList() }
        targets.forEach { it.sendReq(id, filters) }
        withTimeoutOrNull(timeoutMs) { done.await() }
        targets.forEach { it.sendClose(id) }
        queries.remove(id)
        eoses.remove(id)
        return got.toList()
    }
    var publishUrls: List<String> = emptyList()
        private set

    fun setRelays(publish: List<String>, subscribe: List<String>) {
        publishUrls = publish
        val want = (publish + subscribe).toSet()
        relays.keys.filter { it !in want }.forEach { relays.remove(it)?.close(); synchronized(_status) { _status.value = _status.value - it } }
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

    /**
     * Publish to relays that are not in the pool (a DM recipient's inbox): one short connection
     * each, true when at least one answered OK (or duplicate).
     */
    suspend fun publishOnce(urls: List<String>, event: JsonObject, timeoutMs: Long = 10_000): Boolean {
        if (urls.isEmpty()) return false
        val id = event["id"]?.jsonPrimitive?.contentOrNull ?: return false
        val msg = buildJsonArray { add(JsonPrimitive("EVENT")); add(event) }.toString()
        return urls.map { url ->
            scope.async {
                val done = CompletableDeferred<Boolean>()
                val ws = runCatching {
                    http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(msg) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val a = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
                            if (a.getOrNull(0)?.jsonPrimitive?.contentOrNull == "OK" && a.getOrNull(1)?.jsonPrimitive?.contentOrNull == id) {
                                val ok = a.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                                val why = a.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: ""
                                done.complete(ok || why.startsWith("duplicate:"))
                            }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(false) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(false) }
                    })
                }.getOrNull() ?: return@async false
                val r = withTimeoutOrNull(timeoutMs) { done.await() } ?: false
                ws.close(1000, null)
                r
            }
        }.awaitAll().any { it }
    }

    private inner class Relay(val url: String, var subscriber: Boolean) {
        @Volatile var ws: WebSocket? = null
        @Volatile var isConnected = false
        @Volatile private var closed = false
        private var backoff = 2_000L
        @Volatile private var openedAt = 0L
        private val oks = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, String>>>()
        private var job: Job? = null

        fun start() {
            setStatus(url) { it.copy(connected = false) }
            connect()
        }

        private fun connect() {
            if (closed) return
            ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    openedAt = System.currentTimeMillis()
                    setStatus(url) { it.copy(connected = true, since = openedAt) }
                    emit()
                    if (subscriber) subs.forEach { (id, f) -> sendReq(id, f) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped("${t.message}")
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped("closed $code")
            })
        }

        private fun dropped(why: String) {
            // Back off from scratch only after a connection that held; a relay that accepts
            // and drops at once (Cloudflare 52x behind it) otherwise gets hammered every 2 s.
            if (openedAt > 0 && System.currentTimeMillis() - openedAt > 30_000L) backoff = 2_000L
            openedAt = 0L
            isConnected = false
            ws = null
            setStatus(url) { it.copy(connected = false, lastError = why, lastErrorAt = System.currentTimeMillis()) }
            emit()
            oks.values.forEach { it.complete(false to "error: $why") }
            oks.clear()
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

        suspend fun publish(id: String, event: JsonObject): Pair<Boolean, String> {
            val socket = ws ?: return false to "error: not connected"
            val d = CompletableDeferred<Pair<Boolean, String>>()
            oks[id] = d
            val t0 = System.currentTimeMillis()
            socket.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(event) }.toString())
            val r = withTimeoutOrNull(15_000) { d.await() } ?: (false to "error: timeout").also { oks.remove(id) }
            if (r.first) setStatus(url) { it.copy(rttMs = System.currentTimeMillis() - t0) }
            return r
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
                    val sub = msg.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    val ev = (msg.getOrNull(2) as? JsonObject) ?: return
                    val q = sub?.let { queries[it] }
                    if (q != null) { q.first.add(ev); return }
                    setStatus(url) { it.copy(lastEventAt = System.currentTimeMillis()) }
                    scope.launch { onEvent(ev, url) }
                }
                "EOSE" -> {
                    val sub = msg.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return
                    val q = queries[sub] ?: return
                    val seen = eoses[sub] ?: return
                    seen.add(url)
                    if (seen.size >= relays.values.count { it.isConnected }) q.second.complete(Unit)
                }
                "NOTICE", "CLOSED" -> Log.i(TAG, "$url: $text")
            }
        }
    }

    companion object {
        const val TAG = "RelayPool"
        val RETRY_DELAYS_MS = longArrayOf(0, 5_000, 30_000, 120_000)
        val FINAL = listOf("invalid:", "pow:", "blocked:", "restricted:", "auth-required:")
    }
}

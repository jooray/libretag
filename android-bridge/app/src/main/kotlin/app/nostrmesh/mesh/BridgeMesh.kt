package app.nostrmesh.mesh

import android.util.Log
import app.nostrmesh.BuildConfig
import app.nostrmesh.bridge.LoRaPreset
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.RxMeta
import app.nostrmesh.bridge.TxResult
import app.nostrmesh.bridge.Airtime
import io.github.jooray.meshcore.localapi.ApiResponse
import io.github.jooray.meshcore.localapi.ApiResult
import io.github.jooray.meshcore.localapi.MeshCoreApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.nostrmesh.data.Settings
import io.github.jooray.meshcore.localapi.Capabilities
import io.github.jooray.meshcore.localapi.CursorStore
import io.github.jooray.meshcore.localapi.KeyStore
import io.github.jooray.meshcore.localapi.MeshApiSession
import io.github.jooray.meshcore.localapi.MeshEvent
import io.github.jooray.meshcore.localapi.MeshLogger
import io.github.jooray.meshcore.localapi.RadioParams
import io.github.jooray.meshcore.localapi.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.jooray.meshcore.localapi.Route as ApiRoute

/** The bridge's route as the library's. */
fun Route.toApi(): ApiRoute = when (this) {
    Route.Flood -> ApiRoute.Flood
    Route.ZeroHop -> ApiRoute.ZeroHop
    is Route.Direct -> ApiRoute.Direct(path, hashSize)
}

/**
 * What the server said about a received datagram, in the bridge's terms. `queued` (`rx_queued`), `transportCode`
 * and `scope` (`rx_scope`) are taken as the server gives them when it advertises the capability (meshcore-local-api
 * 0.2.0 passes them). Only a server without `rx_queued` but with `rx_path` is read by the old rule: a frame drained
 * from the radio's offline queue comes without `path` and `rssiDbm` (LOCAL_API.md), so it counts as queued and its
 * time is approximate (R22).
 */
fun MeshEvent.ChannelData.meta(caps: Set<String> = emptySet()) = RxMeta(
    hops = hops, path = path, pathHashSize = pathHashSize, routeType = routeType, snrDb = snrDb,
    rssiDbm = rssiDbm, receivedAtMs = receivedAtMs,
    queued = when {
        Capabilities.RX_QUEUED in caps -> queued.takeIf { it }
        Capabilities.RX_PATH in caps && routeType != null && path == null && rssiDbm == null -> true
        else -> null
    },
    transportCode = transportCode.takeIf { Capabilities.RX_SCOPE in caps },
    scope = scope.takeIf { Capabilities.RX_SCOPE in caps },
)

/** `radio.stats.preset` as the bridge's airtime preset; null when invalid. */
fun RadioParams.toPreset(): LoRaPreset? = LoRaPreset(sf, bwHz.toDouble(), cr).takeIf { it.valid }

/**
 * The bridge's link to the MeshCore Local API ([github.com/jooray/meshcore-local-api]): one
 * library [MeshApiSession] for [channel] plus [extraChannels]. The library's config is fixed, so
 * a change of the extra channels replaces the session; [state] and [datagrams] stay the same
 * flows across that. The pairing key and the replay cursor live in [Settings] (`mesh_identity`,
 * `mesh_cursor`); the library advances the cursor itself.
 */
class BridgeMesh(
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val channel: String,
    private val dataTypes: List<Int>,
) {
    private val keyStore = object : KeyStore {
        override fun load() = settings.meshIdentity
        override fun save(value: String) { settings.meshIdentity = value }
    }
    private val cursorStore = object : CursorStore {
        override fun load() = settings.meshCursor
        override fun save(cursor: String) { settings.meshCursor = cursor }
    }
    private val logger = object : MeshLogger {
        override fun debug(tag: String, message: String) { Log.d(tag, message) }
        override fun info(tag: String, message: String) { Log.i(tag, message) }
        override fun warn(tag: String, message: String, error: Throwable?) { Log.w(tag, message, error) }
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // emit() suspends when full, so a replay burst slows the reader instead of being dropped.
    private val _datagrams = MutableSharedFlow<MeshEvent.ChannelData>(extraBufferCapacity = 256)
    val datagrams: SharedFlow<MeshEvent.ChannelData> = _datagrams.asSharedFlow()

    /** LoRa preset from `radio.stats` (only servers with `radio_stats`), else null. */
    @Volatile var preset: LoRaPreset? = null
        private set

    var extraChannels: Map<String, List<Int>> = emptyMap()
        private set

    @Volatile private var running = false
    private var session: MeshApiSession = newSession()
    private var jobs: List<Job> = emptyList()

    /** `hello.capabilities` of the current connection; empty while there is none. */
    val capabilities: Set<String> get() = session.capabilities

    /** When the server last sent anything (watchdog). */
    val lastActivityMs: Long get() = session.lastActivityMs

    /** What MeshCore Open shows in its approval dialog. Loads or creates the key: call off the main thread. */
    val fingerprint: String get() = session.fingerprint

    private fun newSession() = MeshApiSession(
        MeshApiSession.Config(
            clientName = "Nostr Mesh Bridge",
            clientVersion = BuildConfig.VERSION_NAME,
            channel = channel,
            dataTypes = dataTypes,
            extraChannels = extraChannels,
            url = settings.meshUrl,
        ),
        keyStore = keyStore,
        scope = scope,
        cursorStore = cursorStore,
        logger = logger,
    )

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val s = session
        // Collect before starting, so nothing the session emits is missed.
        jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { s.datagrams.collect { _datagrams.emit(it) } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                s.state.collect { st ->
                    _state.value = st
                    if (st is SessionState.Connected) fetchPreset(s)
                }
            },
        )
        s.start()
    }

    @Synchronized
    fun stop() {
        running = false
        session.stop()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _state.value = SessionState.Disconnected
    }

    /** Watchdog: drop the connection and start a fresh one. */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    /** New extra channels: a new session (restarted when running). True when they changed. */
    @Synchronized
    fun setExtraChannels(extras: Map<String, List<Int>>): Boolean {
        if (extras == extraChannels) return false
        val wasRunning = running
        if (wasRunning) stop()
        extraChannels = extras
        session = newSession()
        if (wasRunning) start()
        return true
    }

    private fun fetchPreset(s: MeshApiSession) {
        if (Capabilities.RADIO_STATS !in s.capabilities) return
        scope.launch {
            s.client?.radioStats()?.valueOrNull?.preset?.toPreset()?.let { preset = it }
        }
    }

    /**
     * Sends on any subscribed channel. False when not connected or refused. A [route] other than
     * flood needs `tx_path_data`: without it the library sends nothing (an older server would flood).
     */
    suspend fun sendData(
        channel: String, dataType: Int, payload: ByteArray, route: Route = Route.Flood, region: String? = null,
    ): TxResult {
        val s = session
        if (s.state.value !is SessionState.Connected) {
            return TxResult(false, if (s.state.value == SessionState.NoRadio) "not_connected" else "disconnected")
        }
        // a short zero-hop or direct send (an ACK, a direct command) must not wait behind the send gap: tx_now
        if (route != Route.Flood && TX_NOW in s.capabilities && Capabilities.TX_PATH_DATA in s.capabilities &&
            Airtime.channelPacketBytes(payload.size, (route as? Route.Direct)?.path?.size ?: 0) - 2 <= TX_NOW_MAX_BYTES) {
            val params = MeshCoreApiClient.sendDataParams(channel, dataType, payload, null, route.toApi())
            val r = s.request("channel.sendData", JsonObject(params + ("priority" to JsonPrimitive("now"))))
            return when (r) {
                is ApiResponse.Ok -> TxResult(true)
                is ApiResponse.Error -> TxResult(false, r.error.code)
                ApiResponse.Timeout -> TxResult(false, "timeout")
                else -> TxResult(false, "disconnected")
            }
        }
        // null: the server applies the channel's or the radio's own flood scope
        val r = s.sendData(payload, dataType, channel, region?.trim()?.ifEmpty { null }, route.toApi())
        return when (r) {
            is ApiResult.Ok -> TxResult(true)
            is ApiResult.Failed -> TxResult(false, r.error.code)
        }
    }

    /** `radio.stats` as a probe of the radio itself (servers with `radio_stats`); null when not offered. */
    suspend fun probe(): Boolean? {
        val s = session
        if (Capabilities.RADIO_STATS !in s.capabilities) return null
        return s.client?.radioStats()?.isOk == true
    }

    /** MeshCore Open's own word on its radio: `device.connected`, or the NoRadio state. */
    val radioConnected: Boolean?
        get() = when (state.value) {
            SessionState.NoRadio -> false
            is SessionState.Connected -> session.device.value?.connected ?: true
            else -> null
        }

    companion object {
        const val TX_NOW = "tx_now"
        /** `priority: now` is for payloads of at most 40 B on air. */
        const val TX_NOW_MAX_BYTES = 40
    }
}

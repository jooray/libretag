package app.nostrmesh

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.nostrmesh.bridge.Bridge
import app.nostrmesh.bridge.MeshLink
import app.nostrmesh.bridge.Publisher
import app.nostrmesh.bridge.Route
import app.nostrmesh.bridge.Watchdog
import app.nostrmesh.data.Settings
import app.nostrmesh.data.StateStore
import app.nostrmesh.data.loadOrCreateBridgeKey
import java.io.File
import kotlinx.coroutines.delay
import app.nostrmesh.frames.Frames
import app.nostrmesh.mesh.MeshApiState
import app.nostrmesh.mesh.MeshCoreApiClient
import app.nostrmesh.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The bridge lives in the process, not in an Activity: MeshCore Open (radio) <-> [Bridge] <->
 * [RelayPool] (Nostr). [BridgeService] keeps the process alive while the bridge is on.
 */
class BridgeApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var mesh: MeshCoreApiClient
        private set
    lateinit var relays: RelayPool
        private set
    lateinit var bridge: Bridge
        private set
    private lateinit var store: StateStore

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false
    @Volatile private var watchdog = Watchdog(startedAt = System.currentTimeMillis())

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        relays = RelayPool(scope) { ev, url -> bridge.onRelayEvent(ev, url) }
        mesh = MeshCoreApiClient(settings, scope, Frames.CHANNEL_NOSTR, listOf(Frames.DATA_TYPE_NOSTR))
        val link = object : MeshLink {
            override val maxPayload = Frames.MAX_BLE
            override val capabilities get() = mesh.capabilities
            override val preset get() = mesh.preset
            override suspend fun send(channel: String, dataType: Int, payload: ByteArray, route: Route) =
                mesh.sendData(channel, dataType, payload, route)
        }
        val pub = object : Publisher {
            override suspend fun publish(event: JsonObject) = relays.publish(event)
            override fun subscribe(subId: String, filters: List<JsonObject>) = relays.subscribe(subId, filters)
            override val publishUrls get() = relays.publishUrls
            override suspend fun query(filters: List<JsonObject>) = relays.query(filters)
            override suspend fun publishOnce(event: JsonObject, urls: List<String>) = relays.publishOnce(event, urls)
        }
        // Stable bridge key (PROTOCOL.md 4.1): claims, receipts, announcements. Kept out of backups.
        val bridgeKey = runCatching { loadOrCreateBridgeKey(File(noBackupFilesDir, "bridge.key")) }
            .onFailure { Log.w(TAG, "bridge key unavailable, claims will not count: ${it.message}") }.getOrNull()
        bridge = Bridge(scope, link, pub, settings.bridgeConfig(), devices(), bridgeKey = bridgeKey)
        store = StateStore(File(filesDir, "bridge-state.json"))
        store.load()?.let { bridge.importState(it) }
        // Every mailbox change is saved at once (a command or a cancel must not be lost to a kill);
        // the channel coalesces bursts, the write runs off the caller's thread.
        val saves = Channel<Unit>(Channel.CONFLATED)
        bridge.onMailboxChange = { saves.trySend(Unit) }
        scope.launch(Dispatchers.IO) {
            for (u in saves) {
                store.save(bridge.exportState())
                delay(250)
            }
        }
        createChannel()
        scope.launch {
            mesh.datagrams.collect { d ->
                d.receivedAt?.let { settings.meshCursor = it }
                bridge.onDatagram(d.channel, d.dataType, d.payload, d.meta())
            }
        }
        scope.launch { settings.version.collect { apply() } }
        // Timed mailbox entries (0.7 checks) go out at their `at`.
        scope.launch {
            while (true) {
                delay(500)
                if (running) runCatching { bridge.timedTick() }.onFailure { Log.w(TAG, "timed tick", it) }
            }
        }
        // Once a minute: outbox retries, watchdog, heartbeat; persist (when changed) and move the
        // subscriptions' `since` forward.
        scope.launch {
            var saved = bridge.changeCount
            var tick = 0
            while (true) {
                delay(60_000)
                if (running) runCatching { minuteTick() }.onFailure { Log.w(TAG, "minute tick", it) }
                if (bridge.changeCount != saved) {
                    saved = bridge.changeCount
                    store.save(bridge.exportState())
                }
                if (++tick % 10 == 0 && running) subscribe()
            }
        }
    }

    private fun devices() = settings.devices.mapNotNull { it.toDevice() }

    /**
     * Outbox retries; the radio watchdog (host bridge: exit 3 and the supervisor restarts it; here
     * the Local API client is restarted inside the service); the hourly heartbeat (log line and
     * status.json in app storage, same keys as the host bridge).
     */
    private suspend fun minuteTick() {
        bridge.retryOutbox()
        bridge.announceIfDue()
        val now = System.currentTimeMillis()
        val last = maxOf(bridge.lastRxMs, mesh.lastActivityMs)
        if (watchdog.due(now, last)) {
            Log.w(TAG, "watchdog: nothing from the radio for ${(now - last) / 60_000} min, reconnecting the Local API client")
            bridge.watchdogReconnected()
            mesh.restart()
        }
        if (bridge.heartbeatDue(now)) {
            val (st, line) = bridge.heartbeat(now, last)
            Log.i(TAG, line)
            writeStatus(st)
        }
    }

    private fun writeStatus(st: JsonObject) {
        runCatching {
            val f = File(filesDir, "status.json")
            val tmp = File(filesDir, "status.json.tmp")
            tmp.writeText(st.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "status file: ${it.message}") }
    }

    /** Re-reads settings: profiles, devices, relays, channels; starts or stops everything. */
    @Synchronized
    fun apply() {
        bridge.config = settings.bridgeConfig()
        bridge.devices = devices()
        val extras = buildMap {
            if (settings.uplinkProfiles.any { it.startsWith("road.") }) put(Frames.CHANNEL_ROAD, listOf(Frames.DATA_TYPE_ROAD))
            if ("libretag" in settings.uplinkProfiles || settings.libretagCommands) {
                put(Frames.CHANNEL_LIBRETAG, listOf(Frames.DATA_TYPE_LIBRETAG))
            }
        }
        val channelsChanged = extras != mesh.extraChannels
        mesh.extraChannels = extras
        if (settings.enabled) {
            relays.setRelays(settings.relays, settings.relays)
            subscribe()
            if (!running) {
                watchdog = Watchdog(bridge.config.rxSilenceMs, System.currentTimeMillis())
                writeStatus(bridge.status())
                mesh.start()
                ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java))
                // announcement (kind 38244) at start, then every 24 h from the minute tick
                scope.launch { runCatching { bridge.announceIfDue() }.onFailure { Log.w(TAG, "announce", it) } }
            } else if (channelsChanged) {
                mesh.stop(); mesh.start()
            }
            running = true
        } else if (running) {
            running = false
            store.save(bridge.exportState())
            mesh.stop()
            relays.stop()
            stopService(Intent(this, BridgeService::class.java))
        }
        Log.i(TAG, "applied: enabled=${settings.enabled} channels=${listOf(Frames.CHANNEL_NOSTR) + extras.keys}")
    }

    private val subIds = mutableSetOf<String>()

    @Synchronized
    private fun subscribe() {
        val subs = bridge.subscriptions()
        (subIds - subs.keys).forEach { relays.unsubscribe(it) }
        subs.forEach { (id, f) -> relays.subscribe(id, f) }
        subIds.clear(); subIds.addAll(subs.keys)
    }

    fun meshText(s: MeshApiState): String = when (s) {
        is MeshApiState.Connected -> "MeshCore Open: pripojené" + (s.deviceName?.let { " ($it)" } ?: "") +
            (s.unknownRegion?.let { ", neznámy región $it" } ?: "")
        MeshApiState.Connecting -> "MeshCore Open: pripájam…"
        MeshApiState.Disconnected -> if (settings.enabled) "MeshCore Open: nepripojené (beží? je zapnuté Local API?)" else "MeshCore Open: most je vypnutý"
        MeshApiState.WaitingForApproval -> "Povoľ Nostr most v MeshCore Open"
        MeshApiState.NotApproved -> "MeshCore Open most nepovolil"
        MeshApiState.NoRadio -> "MeshCore Open nie je pripojený k rádiu"
        MeshApiState.WaitingForChannel -> "Pridaj kanál #nostr v MeshCore Open"
        MeshApiState.ChannelDenied -> "Kanál #nostr nebol pridaný"
        is MeshApiState.ChannelMissing -> "Rádio nemá kanál #nostr (${s.code ?: "?"})"
        is MeshApiState.Refused -> "MeshCore Open odmietol odber (${s.code ?: "?"})"
        is MeshApiState.Failed -> "Chyba spojenia: ${s.message}"
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Most beží", NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        const val TAG = "NostrMesh"
        const val CH_SERVICE = "service"
        lateinit var instance: BridgeApp
            private set
    }
}

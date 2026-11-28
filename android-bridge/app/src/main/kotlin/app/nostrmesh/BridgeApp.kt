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
import app.nostrmesh.bridge.TxResult
import app.nostrmesh.bridge.Watchdog
import app.nostrmesh.data.Settings
import app.nostrmesh.data.StateStore
import app.nostrmesh.data.loadOrCreateBridgeKey
import java.io.File
import kotlinx.coroutines.delay
import app.nostrmesh.frames.Frames
import app.nostrmesh.mesh.BridgeMesh
import app.nostrmesh.mesh.meta
import io.github.jooray.meshcore.localapi.SessionState
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
    lateinit var mesh: BridgeMesh
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
        FieldLog.init(getExternalFilesDir(null) ?: filesDir)
        FieldLog.ev("start", "version" to BuildConfig.VERSION_NAME, "adapter" to Bridge.ADAPTER)
        relays = RelayPool(scope) { ev, url -> bridge.onRelayEvent(ev, url) }
        mesh = BridgeMesh(settings, scope, Frames.CHANNEL_NOSTR, listOf(Frames.DATA_TYPE_NOSTR))
        val link = object : MeshLink {
            override val maxPayload = Frames.MAX_BLE
            override val capabilities get() = mesh.capabilities
            override val preset get() = mesh.preset
            // the bridge logs `tx` itself (what, how, at, late_ms, result)
            override suspend fun send(channel: String, dataType: Int, payload: ByteArray, route: Route, region: String?): TxResult =
                mesh.sendData(channel, dataType, payload, route, region)
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
        bridge.onMailboxChange = { saves.trySend(Unit); alarmCheck.trySend(Unit) }
        scope.launch(Dispatchers.IO) {
            for (u in saves) {
                store.save(bridge.exportState())
                delay(250)
            }
        }
        createChannel()
        scope.launch {
            // The library keeps the replay cursor (Settings.meshCursor) itself.
            // the bridge logs `rx` for #libretag itself (route, hops null for DIRECT, first, heard_direct)
            mesh.datagrams.collect { d -> bridge.onDatagram(d.channel, d.dataType, d.payload, d.meta(mesh.capabilities)) }
        }
        // MeshCore Open's own word on its radio (device.connected false, NoRadio): radio health (R37)
        scope.launch {
            mesh.state.collect { st ->
                FieldLog.ev("node_state", "state" to st::class.simpleName, "capabilities" to mesh.capabilities.sorted())
                mesh.radioConnected?.let { bridge.onRadioApi(it) }
            }
        }
        // R24: an exact alarm for the next due timed entry, re-set whenever the mailbox changes
        scope.launch {
            var scheduled: Long? = -1
            for (u in alarmCheck) {
                val next = if (running) bridge.nextDueMs() else null
                if (next != scheduled) {
                    scheduled = next
                    TimedAlarm.schedule(this@BridgeApp, next)
                }
                delay(1_000)
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
        // radio.stats as a probe of the radio itself every 5 minutes (servers with radio_stats only)
        scope.launch {
            while (true) {
                delay(5 * 60_000L)
                if (running) mesh.probe()?.let { bridge.onRadioProbe(it) }
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

    private val alarmCheck = Channel<Unit>(Channel.CONFLATED)

    /**
     * The exact alarm fired [TimedAlarm.LEAD_MS] before a due entry: tick the timed entries with the wake
     * lock held until no entry is due within the next 15 s and no timed send or fallback is in flight.
     */
    fun onTimedAlarm(wl: android.os.PowerManager.WakeLock) {
        scope.launch {
            try {
                val until = System.currentTimeMillis() + TimedAlarm.MAX_HOLD_MS - 5_000
                while (System.currentTimeMillis() < until) {
                    if (running) runCatching { bridge.timedTick() }
                    val next = bridge.nextDueMs()
                    val soon = next != null && next - System.currentTimeMillis() < 15_000
                    if (!soon && !bridge.busy) break
                    delay(250)
                }
            } finally {
                runCatching { if (wl.isHeld) wl.release() }
                alarmCheck.trySend(Unit)
            }
        }
    }

    /**
     * Outbox retries; the radio watchdog (host bridge: exit 3 and the supervisor restarts it; here
     * the Local API client is restarted inside the service); the hourly heartbeat (log line and
     * status.json in app storage, same keys as the host bridge).
     */
    private suspend fun minuteTick() {
        bridge.retryOutbox()
        bridge.radioTick()
        bridge.clockTick()
        bridge.announceIfDue()
        alarmCheck.trySend(Unit)
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
                // 0xFF6C: other bridges' fallback floods, so a command heard on air is not sent again
                put(Frames.CHANNEL_LIBRETAG, listOf(Frames.DATA_TYPE_LIBRETAG, app.nostrmesh.libretag.QuietMesh.DATA_TYPE_RETRY))
            }
        }
        // A change of channels replaces the Local API session (restarted when it runs).
        mesh.setExtraChannels(extras)
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
            }
            running = true
        } else if (running) {
            running = false
            FieldLog.ev("stop", "version" to BuildConfig.VERSION_NAME)
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

    fun meshText(s: SessionState): String = when (s) {
        is SessionState.Connected -> "MeshCore Open: pripojené" + (s.deviceName?.let { " ($it)" } ?: "") +
            (s.unknownRegion?.let { ", neznámy región $it" } ?: "")
        SessionState.Connecting -> "MeshCore Open: pripájam…"
        SessionState.Disconnected -> if (settings.enabled) "MeshCore Open: nepripojené (beží? je zapnuté Local API?)" else "MeshCore Open: most je vypnutý"
        SessionState.WaitingForApproval -> "Povoľ Nostr most v MeshCore Open"
        SessionState.NotApproved -> "MeshCore Open most nepovolil"
        SessionState.NoRadio -> "MeshCore Open nie je pripojený k rádiu"
        SessionState.WaitingForChannel -> "Pridaj kanál #nostr v MeshCore Open"
        SessionState.ChannelDenied -> "Kanál #nostr nebol pridaný"
        is SessionState.ChannelMissing -> "Rádio nemá kanál #nostr (${s.code ?: "?"})"
        is SessionState.Refused -> "MeshCore Open odmietol odber (${s.code ?: "?"})"
        is SessionState.Failed -> "Chyba spojenia: ${s.message}"
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

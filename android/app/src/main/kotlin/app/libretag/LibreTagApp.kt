package app.libretag

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.libretag.data.AnchorRec
import app.libretag.data.AppPrefs
import app.libretag.data.CommandResult
import app.libretag.data.EstimateRec
import app.libretag.data.NeighbourRec
import app.libretag.data.PendingCommand
import app.libretag.data.Position
import app.libretag.data.Status
import app.libretag.data.StatusKind
import app.libretag.data.Tracker
import app.libretag.data.TrackerStore
import app.libretag.keys.KeyChain
import app.libretag.keys.OwnerChain
import app.libretag.keys.SharedChain
import app.libretag.keys.TagWindow
import app.libretag.locate.AnchorResolver
import app.libretag.locate.Estimator
import app.libretag.locate.Geo
import app.libretag.mesh.MeshLink
import io.github.jooray.meshcore.localapi.MeshEvent
import io.github.jooray.meshcore.localapi.SessionState
import app.libretag.nostr.Bech32
import app.libretag.nostr.Nip04
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.RelayPool
import app.libretag.proto.Command
import app.libretag.proto.Keys
import app.libretag.proto.LibreTag
import app.libretag.proto.Mode
import app.libretag.proto.Report
import app.libretag.proto.ReportExtras
import app.libretag.ui.Format
import app.libretag.ui.MainActivity
import app.libretag.usb.UsbTagSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.osmdroid.config.Configuration
import java.security.SecureRandom
import java.util.Base64

/** The four commands of the brief, the Bluetooth scan, and the report interval (PROTOCOL.md 3). */
enum class CommandKind { LOST, TRACK, BEEP, NORMAL, BLE_SCAN, INTERVAL, BLE_CONSOLE;
    /**
     * [arg]: TRACK interval in seconds, INTERVAL minutes (0 = the default); [durationMin]: how long TRACK
     * runs (0 = 1 h); [ackPatience] (INTERVAL, 0.10.0): 1 or 2, 0 keeps the tag's value
     */
    fun build(seq: Int, arg: Int = 0, checkS: Int = 0, reportEvery: Int = 0, durationMin: Int = 0, ackPatience: Int = 0): Command = when (this) {
        LOST -> Command.setMode(seq, Mode.LOST, Status.LOST_INTERVAL_S, LOST_MIN)
        TRACK -> Command.setMode(seq, Mode.TRACK, if (arg > 0) arg else Status.TRACK_INTERVAL_S, if (durationMin > 0) durationMin else TRACK_MIN)
        BEEP -> Command.buzz(seq, 10)
        NORMAL -> Command.setMode(seq, Mode.IDLE)
        BLE_SCAN -> Command.bleScan(seq, 0, false, 2)
        INTERVAL -> Command.setIdle(seq, if (arg > 0) arg else 60, 0, checkS, reportEvery, ackPatience)
        BLE_CONSOLE -> Command.bleConsole(seq, arg)
    }
    /**
     * The command's lifetime (SPEC.md 3.2): Beep 1 h, Bluetooth 4 h, Track, Normal and scan 12 h, Lost and
     * settings 48 h. Its mailbox entries expire with it, and at its end the owner is told (R7).
     */
    val mailboxTtlS: Long get() = when (this) { BEEP -> 3600L; BLE_CONSOLE -> 4 * 3600L; LOST, INTERVAL -> 48 * 3600L; else -> 12 * 3600L }
    companion object {
        const val LOST_MIN = 1440
        const val TRACK_MIN = 60
        fun of(name: String): CommandKind? = entries.firstOrNull { it.name == name }
    }
}

/** How the app reaches tags right now (the chip in the top bar). */
enum class Reach { MESH, NOSTR, OFFLINE }

/**
 * Holds the store, the MeshCore Open connection and the relays for the whole process. Reports
 * are processed here (not in an Activity) so they are stored and alarms fire while the app is in
 * the background; [TrackerService] keeps the process alive. Protocol: PROTOCOL.md v2 (LT2).
 */
class LibreTagApp : Application() {
    lateinit var store: TrackerStore
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var mesh: MeshLink
        private set
    lateinit var relays: RelayPool
        private set
    lateinit var usb: UsbTagSession
        private set
    /** 0.8.0: console adverts of our tags, while a screen asks for them */
    val ble by lazy { app.libretag.ble.BleConsoleScanner(this) }
    /** public repeater map, downloaded once, for positions without GPS */
    val repeaters by lazy { app.libretag.locate.RepeaterDb(this) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** commands over Bluetooth advertising while one waits (PROTOCOL.md 3.1) */
    val bleBeacon by lazy { app.libretag.ble.BleCommandBeacon(this) }
    /** 0.13.1: the wake advert that opens a nearby tag's console without the report counter (firmware 0.9.5) */
    val bleWake by lazy { app.libretag.ble.BleWakeBeacon(this) }
    private val windows = mutableMapOf<String, TagWindow>()
    private val keyCache = mutableMapOf<String, Keys>()
    private val random = SecureRandom()
    /** our 4244 events, for cancelling them (NIP-09, signed with the same sk_n) and for bridge claims */
    val mailboxKeys by lazy { app.libretag.nostr.MailboxKeys(noBackupFilesDir.resolve("mailbox_keys.json")) }
    /** anchors this app keeps published for the next reports of each tag (PROTOCOL.md 4.2) */
    private val anchorBook by lazy { app.libretag.nostr.AnchorBook(noBackupFilesDir.resolve("anchors.json")) }

    private val _reach = MutableStateFlow(Reach.OFFLINE)
    val reach: StateFlow<Reach> = _reach
    /** when the last datagram came from MeshCore Open (ms) */
    val lastMeshPacket = MutableStateFlow(0L)
    /** receive times of our reports that bridges published to Nostr (Connections: "bridges") */
    val bridgeReports = MutableStateFlow<List<Long>>(emptyList())
    /** a command a tag confirmed: (tag id, kind), for the support ask's moment of value */
    private val _confirmed = MutableSharedFlow<Pair<String, CommandKind>>(extraBufferCapacity = 8)
    val confirmed: SharedFlow<Pair<String, CommandKind>> = _confirmed
    /** repeater map changed (downloaded): estimates are redone */
    val repeatersVersion = MutableStateFlow(0L)
    /** the owner's Nostr signer (for the backup copy to their npub) */
    val signers by lazy { app.libretag.signer.SignerStore(this) }
    /** both key backup copies (SPEC.md 12.2) */
    val backup by lazy { app.libretag.backup.BackupSync(this) }

    // ---------------------------------------------------------------- 0.12 tag diagnosis
    /** the phone has a usable default network (ConnectivityManager) */
    val online = MutableStateFlow(true)
    /** since when the phone can receive reports (docs/design/tag-diagnosis.md 4.5) */
    val pathLog = app.libretag.data.PathLog()
    /** the phone's clock against reports' fix times and relay event times (ms), null = no evidence */
    val clockSkewMs = MutableStateFlow<Long?>(null)
    /** 0.14.0 (R13): the bridge that heard the tag is offline (announcement older than 2 h, or radio down), by tag id */
    val bridgeDown = MutableStateFlow<Map<String, app.libretag.data.BridgeDownSeen>>(emptyMap())
    /** bridge receipts for counters above the newest opened one, by tag id (4.4) */
    val receipts = MutableStateFlow<Map<String, app.libretag.data.ReceiptSeen>>(emptyMap())
    /** what is on the USB cable, app-wide (4.2) */
    val cable by lazy { app.libretag.usb.CableWatch(this) }
    private val _diag = MutableStateFlow<Map<String, app.libretag.data.Diagnosis.Result>>(emptyMap())
    /** the diagnosis of every late tag: the list, the detail, the map and the notification read this one value */
    val diag: StateFlow<Map<String, app.libretag.data.Diagnosis.Result>> = _diag
    /** bumps when the diagnosis inputs change (path, cable, sighting, receipt) */
    val diagContextVersion = MutableStateFlow(0L)

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        instance = this
        Configuration.getInstance().userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
        Configuration.getInstance().osmdroidBasePath = filesDir.resolve("osmdroid")
        Configuration.getInstance().osmdroidTileCache = cacheDir.resolve("tiles")
        // 0.8.0: secrets wrapped with a Keystore key; must be in place before any store loads
        app.libretag.keys.Secrets.box = app.libretag.keys.KeystoreKey.box()
        if (app.libretag.keys.Secrets.box == null) DiagLog.w(TAG, "Keystore unavailable: secrets stay unwrapped")
        store = TrackerStore(this)
        prefs = AppPrefs(this)
        foldGlobalAlerts()
        applyTheme()
        mesh = MeshLink(store, scope)
        usb = UsbTagSession(this)
        // the tag left the cable: drop the session wherever the app is
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) { usb.usbDetached() }
        }, android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        createChannels()
        // 0.12: a restart of the process was blind since the last tick
        prefs.aliveAt.takeIf { it > 0 }?.let { pathLog.blindFrom = it }
        cable.start()
        watchNetwork()
        scope.launch { mesh.datagrams.collect { onDatagram(it) } }
        scope.launch { mesh.state.collect { st -> onNodeState(st) } }
        // Reports any bridge published (kind 4243) and our own command mailbox (kind 4244).
        relays = RelayPool(scope) { ev, _ -> onNostrEvent(ev) }
        relays.setRelays(store.relays, store.relays)
        scope.launch { store.trackers.collect { resubscribe(it); SilenceAlarm.schedule(this@LibreTagApp, it); ReportAlarm.schedule(this@LibreTagApp, it) } }
        scope.launch { watchSilence() }
        scope.launch { watchSlots() }
        scope.launch { watchCommands() }
        scope.launch { bleBeacon.run() } // 0.7.7: pending commands in this phone's Bluetooth advertising
        scope.launch { bleWake.run() }
        scope.launch { watchReach() }
        scope.launch { watchReports() }
        backup.start()
        scope.launch { retryDeletions() }
        subscribeClaims()
        scope.launch { delay(3_000); ensureAllEstimates() }
        scope.launch { store.trackers.collect { recomputeDiag() } }
        scope.launch { cable.state.collect { recomputeDiag() } }
        scope.launch { diagContextVersion.collect { recomputeDiag() } }
        scope.launch { usb.state.collect { recomputeDiag() } }
        scope.launch {
            while (true) {
                app.libretag.ble.BleSightScan.update(this@LibreTagApp)
                checkNotHere()
                recomputeDiag()
                delay(30_000)
            }
        }
    }

    /** "Check if it's here" ran out without an advert: kept as a negative result for one period. */
    private fun checkNotHere() {
        val now = System.currentTimeMillis()
        for (t in store.trackers.value) if (app.libretag.ble.BleBoots.notHere(t, now)) {
            DiagLog.i(TAG, "${t.name}: not heard over Bluetooth by its wake-up")
            store.updateTracker(t.id) { it.copy(hereAskAt = null, notHereAt = now) }
        }
    }

    /** "Check if it's here": the console command over Bluetooth, and the time the tag should answer by. */
    fun checkHere(t: Tracker) {
        val now = System.currentTimeMillis()
        val at = app.libretag.data.Diagnosis.nextWake(t, now) ?: (now + t.intervalMin * 60_000L)
        store.updateTracker(t.id) { it.copy(hereAskAt = at, notHereAt = null) }
        diagContextVersion.value++
    }

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        online.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) {
                    val on = c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (online.value != on) { online.value = on; diagContextVersion.value++ }
                }
                override fun onLost(n: android.net.Network) { online.value = false; diagContextVersion.value++ }
            })
        }.onFailure { DiagLog.w(TAG, "network callback: ${it.message}") }
    }

    /** The relays answered the query for the tags' windows: the phone can see again (PathLog). */
    private fun markBackfilled() {
        pathLog.backfilled(System.currentTimeMillis())
        diagContextVersion.value++
    }

    /** The tag a bootloader or a download-mode ESP32 on the cable belongs to, null when none fits. */
    fun cableTagId(now: Long = System.currentTimeMillis()): String? {
        val raw = cable.state.value.raw
        if (raw != app.libretag.usb.CableClassify.Raw.BOOTLOADER && raw != app.libretag.usb.CableClassify.Raw.CP2102) return null
        val list = store.trackers.value
        val overdue = list.filter { app.libretag.data.Diagnosis.late(it, now) }.map { it.id }.toSet()
        return app.libretag.usb.CableClassify.attribute(raw == app.libretag.usb.CableClassify.Raw.CP2102, list, overdue, now)?.id
    }

    /** a bootloader on the cable that fits none of the tags (the home strip, 7.4) */
    fun cableUnattributed(): Boolean =
        cable.state.value.raw == app.libretag.usb.CableClassify.Raw.BOOTLOADER && cableTagId() == null

    fun bluetoothReady(): Boolean = runCatching {
        val a = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        a != null && a.isEnabled && (android.os.Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    fun diagContext(t: Tracker, now: Long = System.currentTimeMillis()): app.libretag.data.DiagContext {
        val (up, all) = relays.connected.value
        val ms = mesh.state.value
        val link = when {
            ms is SessionState.Connected -> app.libretag.data.MeshLink.UP
            store.meshIdentity == null -> app.libretag.data.MeshLink.NEVER
            else -> app.libretag.data.MeshLink.DOWN
        }
        val cs = cable.state.value
        val session = usb.connected?.takeIf { it.via == app.libretag.usb.Via.USB }?.tagId
        return app.libretag.data.DiagContext(
            online = online.value, relaysUp = up, relaysAll = all, mesh = link,
            meshUpAt = prefs.meshUpAt.takeIf { it > 0 }, linkUp = pathLog.linkUp, pathUpSince = pathLog.upSince,
            blindFrom = pathLog.blindFrom, backfillAt = pathLog.backfillAt,
            cable = app.libretag.usb.CableClassify.cableFor(t, cs.raw, cs.espDownload, session, cableTagId(now)),
            receipt = receipts.value[t.id], clockSkewMs = clockSkewMs.value, minFirmware = MIN_FIRMWARE,
            bridgeDown = bridgeDown.value[t.id],
            bluetooth = bluetoothReady(),
        )
    }

    @Synchronized
    fun recomputeDiag() {
        val now = System.currentTimeMillis()
        val out = mutableMapOf<String, app.libretag.data.Diagnosis.Result>()
        for (t in store.trackers.value) {
            val d = app.libretag.data.Diagnosis.of(t, Status.of(t, now), diagContext(t, now), now) ?: continue
            out[t.id] = d
        }
        if (out != _diag.value) _diag.value = out
    }

    /**
     * 0.9: the app-wide alert switches are gone (one place per question: Tag settings). A switch
     * someone had turned off is carried into every tag's own switch, then the global one is set
     * back on, so nothing stays muted behind a switch nobody can see. Leaving a place follows
     * the place's own choice; guard and Lost always notify. Idempotent; the only reader of
     * [AppPrefs.notifySilent] and its siblings.
     */
    private fun foldGlobalAlerts() {
        val p = prefs
        if (p.notifySilent && p.notifyBattery && p.notifyZone && p.notifyGuard && p.notifyLostFound) return
        val silent = p.notifySilent
        val batt = p.notifyBattery
        val zone = p.notifyZone
        store.update { list ->
            list.map { t ->
                t.copy(
                    alerts = t.alerts.copy(silent = t.alerts.silent && silent, lowBattery = t.alerts.lowBattery && batt),
                    zones = if (zone) t.zones else t.zones.map { it.copy(notifyLeave = it.notifyLeave ?: false) },
                    wifiZones = if (zone) t.wifiZones else t.wifiZones.map { it.copy(notifyLeave = it.notifyLeave ?: false) },
                )
            }
        }
        p.notifySilent = true; p.notifyBattery = true; p.notifyZone = true; p.notifyGuard = true; p.notifyLostFound = true
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(when (prefs.theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    private var lastMeshOk = false

    private suspend fun watchReach() {
        while (true) {
            val meshOk = mesh.state.value is SessionState.Connected
            val nostrOk = relays.connected.value.first > 0
            _reach.value = when {
                meshOk -> Reach.MESH
                nostrOk -> Reach.NOSTR
                else -> Reach.OFFLINE
            }
            // 0.12: the path log (the phone's own blindness is not the tag's silence)
            val now = System.currentTimeMillis()
            if (pathLog.update(meshOk || nostrOk, now)) {
                if (!pathLog.linkUp) prefs.blindFrom = pathLog.blindFrom ?: now
                diagContextVersion.value++
            }
            // the own node replays from its cursor: connected is caught up
            if (meshOk && pathLog.blind && pathLog.linkUp) markBackfilled()
            if (meshOk && now - prefs.meshUpAt > 60_000L) prefs.meshUpAt = now
            if (!pathLog.blind && now - prefs.aliveAt > 60_000L) prefs.aliveAt = now
            if (meshOk != lastMeshOk) { lastMeshOk = meshOk; diagContextVersion.value++ }
            delay(1_000)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_alerts_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LOST, getString(R.string.channel_lost), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_lost_desc)
            }
        )
        // 0.9: leaving and arriving at a place are information, not alarms: default importance
        nm.createNotificationChannel(
            NotificationChannel(CH_PLACES, getString(R.string.channel_places), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_places_desc)
            }
        )
    }

    fun keysFor(t: Tracker): Keys? = synchronized(keyCache) {
        if (t.keyHex.isEmpty()) return null
        keyCache[t.keyHex] ?: LibreTag.hexToBytes(t.keyHex)?.takeIf { it.size == LibreTag.KEY_SIZE }
            ?.let { Keys(it) }?.also { keyCache[t.keyHex] = it }
    }

    /** The tag's console key: from K0 for our own tags, from the link for a full-access share. */
    fun consoleFor(t: Tracker): app.libretag.keys.ConsoleKey? =
        keysFor(t)?.let { app.libretag.keys.ConsoleKey.of(it) } ?: app.libretag.keys.ConsoleKey.shared(t)

    fun chainFor(t: Tracker): KeyChain? = if (t.isShared) SharedChain.of(t) else keysFor(t)?.let { OwnerChain(it) }

    @Synchronized
    private fun windowFor(t: Tracker): TagWindow? {
        val w = windows[t.id]
        if (w != null && w.lastN >= t.lastN) return w
        val chain = chainFor(t) ?: return null
        // a report known only from an approximate copy stays open for an exact one (0.8.1)
        return TagWindow(chain, t.lastN, t.positions.filter { !it.approx }.map { it.n }.filter { it >= 0 }).also { windows[t.id] = it }
    }

    @Synchronized
    fun forgetWindow(id: String) { windows.remove(id); subscribedAt.remove(id) }

    // ---------------------------------------------------------------- Nostr

    private val subscribedAt = mutableMapOf<String, Long>()

    private fun windowMode(): Boolean = store.reportWindowUntil > System.currentTimeMillis()

    @Synchronized
    private fun resubscribe(trackers: List<Tracker>) {
        subscribeTagDms(trackers)
        if (!windowMode()) {
            // 0.8.0: all kind 4243 live, matched here; no per-tag tags go to the relays
            if (trackers.isNotEmpty()) ensureLiveAll()
            trackers.filter { it.lastN < 0 && it.id !in initialSynced }.takeIf { it.isNotEmpty() }?.let { fresh ->
                initialSynced += fresh.map { it.id }
                scope.launch { syncBacklog(System.currentTimeMillis() / 1000 - FIRST_SYNC_S) }
            }
            return
        }
        for (t in trackers) {
            if (subscribedAt[t.id] == t.lastN) continue
            val w = windowFor(t) ?: continue
            subscribedAt[t.id] = t.lastN
            val tags = w.tagsAhead(if (t.lastN < 0) 256 else 64)
            val since = System.currentTimeMillis() / 1000 - 14 * 86400
            relays.subscribe("lt-" + t.id.take(8), listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(LibreTag.NOSTR_REPORT_KIND)) })
                put("#y", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
                put("since", since)
            }))
        }
    }

    private suspend fun onNostrEvent(raw: JsonObject) {
        val ev = NostrEvent.fromJson(raw) ?: return
        if (ev.kind == app.libretag.nostr.MailboxKeys.KIND_CLAIM) { onClaim(ev); return }
        if (ev.kind == 4) { onTagDm(ev); return }
        if (ev.kind != LibreTag.NOSTR_REPORT_KIND) return
        if (!windowMode() && liveRate.add(System.currentTimeMillis()) > app.libretag.nostr.ReportSync.LIVE_MAX_PER_HOUR) toWindowMode()
        if (ev.tags.none { it.size >= 2 && it[0] == "approx" }) noteRelayClock(ev.createdAt)
        onReportEvent(ev)
    }

    /** A kind 4243 from the live stream, a backlog page or a window query: open it if it is ours. */
    private suspend fun onReportEvent(ev: NostrEvent) {
        // 0.12: a relay event from the future: the phone's clock is behind
        (System.currentTimeMillis() - ev.createdAt * 1000).let { if (it < -app.libretag.data.Diagnosis.CLOCK_SKEW_MS) noteClock(it) }
        val p = try { Base64.getDecoder().decode(ev.content) } catch (e: IllegalArgumentException) { return }
        if (p.size == LibreTag.BLE_P_LEN || p.size == LibreTag.BLE_P_LEN_STEALTH) { if (ev.verify()) onExtra(p); return }
        if (p.size != LibreTag.REPORT_P_LEN && p.size != LibreTag.REPORT_P_LEN_STEALTH) return
        // match first (a table lookup), verify only ours: in the fetch-all query most events are other people's
        for (t in store.trackers.value) {
            val w = windowFor(t) ?: continue
            if (!w.knows(p)) continue
            if (!ev.verify()) return
            val (at, approxUntil) = app.libretag.nostr.ReportSync.reportTimeMs(ev)
            val (n, jj, r) = w.tryOpen(p, keep = approxUntil != null) ?: return
            DiagLog.i(TAG, "${t.name}: report n=$n j=$jj from a relay" + if (approxUntil != null) " (approximate time)" else "")
            DiagLog.ev("open", "tag" to t.id, "via" to "relay", "n" to n, "j" to jj, "x" to p.copyOf(4), "id" to ev.id,
                "pubkey" to ev.pubkey, "created_at" to ev.createdAt, "approx_until_ms" to approxUntil)
            bridgeReports.value = (bridgeReports.value + System.currentTimeMillis()).takeLast(500)
            handleReport(t, n, r, hops = null, snrDb = 0.0, via = "nostr", receivedAtMs = at,
                approxUntilMs = approxUntil, j = jj, x = LibreTag.bytesToHex(p.copyOf(4)))
            return
        }
        // P8: another copy of a report opened in the last 120 s (the own node had it first, or another j)
        for (t in store.trackers.value) {
            val w = windowFor(t) ?: continue
            val (n, jj, r) = w.trySecond(p) ?: continue
            if (!ev.verify()) return
            val (at, approxUntil) = app.libretag.nostr.ReportSync.reportTimeMs(ev)
            secondSighting(t, n, jj, r, via = "nostr", receivedAtMs = at, approx = approxUntil != null,
                x = LibreTag.bytesToHex(p.copyOf(4)), nodeDirect = false)
            return
        }
        if (ev.verify()) app.libretag.nostr.ReportSync.reportTimeMs(ev).let { (at, until) -> rememberUnmatched(p, at, "nostr", until) }
    }

    // ---------------------------------------------------------------- private report query (0.8.0)

    private val liveRate = app.libretag.nostr.ReportSync.Rate()
    private val initialSynced = mutableSetOf<String>()
    private var liveSince = 0L
    private val syncLock = Mutex()

    @Synchronized
    private fun ensureLiveAll(force: Boolean = false) {
        val nowS = System.currentTimeMillis() / 1000
        if (!force && liveSince > 0) return
        liveSince = nowS
        relays.subscribe("lt-all", listOf(buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(LibreTag.NOSTR_REPORT_KIND)) })
            put("since", nowS - 60)
        }))
    }

    /** Too many reports on the network for a phone: back to the per-tag window query for a day. */
    @Synchronized
    private fun toWindowMode() {
        if (windowMode()) return
        DiagLog.w(TAG, "report stream above ${app.libretag.nostr.ReportSync.LIVE_MAX_PER_HOUR}/h: window query for 24 h")
        store.reportWindowUntil = System.currentTimeMillis() + app.libretag.nostr.ReportSync.WINDOW_MODE_MS
        relays.unsubscribe("lt-all")
        liveSince = 0
        subscribedAt.clear()
        resubscribe(store.trackers.value)
    }

    /**
     * Keeps the fetch-all query going: the live subscription is renewed every [SYNC_EVERY_MS] (so a
     * reconnect doesn't replay hours), and each time, and after every reconnect, the backlog since the
     * last sync is fetched page by page.
     */
    private suspend fun watchReports() {
        var wasOnline = false
        var lastRun = 0L
        while (true) {
            delay(10_000)
            // a failed sync (a relay dropped mid-query, a bad event) must not end the loop: the
            // diagnosis would say "checking for missed reports" for good
            val ok = guarded({ DiagLog.w(TAG, "report sync failed, retrying: ${it.javaClass.simpleName}: ${it.message}") }) {
                val online = relays.connected.value.first > 0
                val now = System.currentTimeMillis()
                if (!online) { wasOnline = false; return@guarded }
                // the window subscriptions replay 14 days: connected is caught up
                if (windowMode()) { if (!wasOnline) markBackfilled(); wasOnline = true; return@guarded }
                if (store.reportWindowUntil != 0L) {
                    // the window period ended: back to fetching everything
                    store.reportWindowUntil = 0
                    store.trackers.value.forEach { relays.unsubscribe("lt-" + it.id.take(8)) }
                    subscribedAt.clear()
                    resubscribe(store.trackers.value)
                }
                if (!wasOnline || now - lastRun > SYNC_EVERY_MS) {
                    wasOnline = true
                    lastRun = now
                    if (store.trackers.value.isEmpty()) return@guarded
                    ensureLiveAll(force = true)
                    val last = store.reportSyncAt
                    syncBacklog(if (last > 0) last - app.libretag.nostr.ReportSync.OVERLAP_S else now / 1000 - FIRST_SYNC_S)
                }
            }
            // try again at the next pass
            if (ok == null) wasOnline = false
        }
    }

    /** Every kind 4243 from [sinceS] to now, page by page; past the cap the rest by the window query. */
    private suspend fun syncBacklog(sinceS: Long) = syncLock.withLock {
        val nowS = System.currentTimeMillis() / 1000
        val from = maxOf(sinceS, nowS - FIRST_SYNC_S)
        if (relays.connected.value.first == 0) return@withLock
        val res = app.libretag.nostr.ReportSync.backlog(from, nowS, { s, u, l ->
            relays.query(listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(LibreTag.NOSTR_REPORT_KIND)) })
                put("since", s); put("until", u); put("limit", l)
            }), 15_000).mapNotNull { NostrEvent.fromJson(it) }
        })
        for (e in res.events) onReportEvent(e)
        DiagLog.i(TAG, "report sync: ${res.events.size} events since ${nowS - from} s ago" + if (res.complete) "" else ", cap reached, window query for the rest")
        if (!res.complete) windowQuery(from, res.uncoveredUntil)
        store.reportSyncAt = maxOf(store.reportSyncAt, nowS)
        markBackfilled()
    }

    /** Started by the backup alarm: wait for a relay and fetch what came since the last sync. */
    suspend fun quickSync() {
        while (relays.connected.value.first == 0) delay(250)
        if (windowMode()) return
        ensureLiveAll()
        val last = store.reportSyncAt
        syncBacklog(if (last > 0) last - app.libretag.nostr.ReportSync.OVERLAP_S else System.currentTimeMillis() / 1000 - FIRST_SYNC_S)
    }

    /** The pre-0.8 query for one time range: each tag's own `#y` window. */
    private suspend fun windowQuery(sinceS: Long, untilS: Long) {
        for (t in store.trackers.value) {
            val w = windowFor(t) ?: continue
            for (chunk in w.tagsAhead(if (t.lastN < 0) 256 else 64).chunked(WINDOW_CHUNK)) {
                val got = relays.query(listOf(buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(LibreTag.NOSTR_REPORT_KIND)) })
                    put("#y", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                    put("since", sinceS); put("until", untilS)
                }), 15_000).mapNotNull { NostrEvent.fromJson(it) }.sortedBy { it.createdAt }
                for (e in got) onReportEvent(e)
            }
        }
    }

    // ---------------------------------------------------------------- resync (0.8.0)

    private class Unmatched(val p: ByteArray, val atMs: Long, val via: String, val approxUntilMs: Long? = null)

    /** reports nobody's window opened, by tag (hex of x): other people's, or ours past the window */
    private val unmatched = object : LinkedHashMap<String, Unmatched>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unmatched>?) = size > UNMATCHED_MAX
    }

    private fun rememberUnmatched(p: ByteArray, atMs: Long, via: String, approxUntilMs: Long? = null) {
        if (p.size != LibreTag.REPORT_P_LEN && p.size != LibreTag.REPORT_P_LEN_STEALTH) return
        synchronized(unmatched) {
            val key = LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN))
            // an exact copy already waiting wins over an approximate one
            if (approxUntilMs != null && unmatched[key]?.approxUntilMs == null && unmatched.containsKey(key)) return
            unmatched[key] = Unmatched(p, atMs, via, approxUntilMs)
        }
    }

    private val resyncAt = mutableMapOf<String, Long>()
    @Volatile private var unmatchedFilled = false
    private val resyncLock = Mutex()

    /** A silent tag may have run past the 2048-report window: search forward (blocks up to [TagWindow.RESYNC_CAP]). */
    private fun maybeResync(t: Tracker, now: Long) {
        synchronized(resyncAt) {
            if (now - (resyncAt[t.id] ?: 0L) < RESYNC_EVERY_MS) return
            resyncAt[t.id] = now
        }
        scope.launch { resyncLock.withLock { resync(t.id) } }
    }

    private suspend fun resync(id: String) {
        val t = store.get(id) ?: return
        val w = windowFor(t) ?: return
        val has = { x: String -> synchronized(unmatched) { unmatched.containsKey(x) } }
        var hit = w.searchForward(has)
        if (hit == null && !windowMode() && !unmatchedFilled) {
            // the cache lives in memory: once per process, refill it with the last two weeks, then look again
            unmatchedFilled = true
            syncBacklog(System.currentTimeMillis() / 1000 - FIRST_SYNC_S)
            hit = (windowFor(store.get(id) ?: return) ?: return).searchForward(has)
        }
        if (hit == null && windowMode()) {
            // window query mode: ask the relays block by block
            val base = if (t.lastN < 0) TagWindow.FIRST_SYNC else t.lastN + 1 + TagWindow.AHEAD
            var from = base
            val since = System.currentTimeMillis() / 1000 - FIRST_SYNC_S
            while (hit == null && from < maxOf(t.lastN, 0) + 1 + TagWindow.RESYNC_CAP) {
                for (chunk in w.blockTags(from, TagWindow.AHEAD).chunked(WINDOW_CHUNK)) {
                    relays.query(listOf(buildJsonObject {
                        put("kinds", buildJsonArray { add(JsonPrimitive(LibreTag.NOSTR_REPORT_KIND)) })
                        put("#y", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                        put("since", since)
                    }), 15_000).mapNotNull { NostrEvent.fromJson(it) }.filter { it.verify() }.forEach { e ->
                        runCatching { Base64.getDecoder().decode(e.content) }.getOrNull()?.let {
                            val (at, until) = app.libretag.nostr.ReportSync.reportTimeMs(e)
                            rememberUnmatched(it, at, "nostr", until)
                        }
                    }
                }
                hit = w.searchForward(has, cap = from + TagWindow.AHEAD - maxOf(t.lastN, 0) - 1)
                from += TagWindow.AHEAD
            }
        }
        val (n, j, x) = hit ?: run { DiagLog.i(TAG, "${t.name}: resync found nothing up to +${TagWindow.RESYNC_CAP}"); return }
        val u = synchronized(unmatched) { unmatched.remove(x) } ?: return
        val r = w.openAt(u.p, n, j, keep = u.approxUntilMs != null) ?: return
        DiagLog.i(TAG, "${t.name}: resync, report n=$n (was ${t.lastN})")
        handleReport(t, n, r, hops = null, snrDb = 0.0, via = u.via, receivedAtMs = u.atMs, approxUntilMs = u.approxUntilMs)
        // later reports of the same tag that waited in the cache open now with the moved window
        val rest = synchronized(unmatched) { unmatched.entries.sortedBy { it.value.atMs }.map { it.key to it.value } }
        for ((key, v) in rest) {
            val cur = store.get(id) ?: return
            val ww = windowFor(cur) ?: return
            val (n2, _, r2) = ww.tryOpen(v.p, keep = v.approxUntilMs != null) ?: continue
            synchronized(unmatched) { unmatched.remove(key) }
            handleReport(cur, n2, r2, hops = null, snrDb = 0.0, via = v.via, receivedAtMs = v.atMs, approxUntilMs = v.approxUntilMs)
        }
    }


    private fun notifyCritical(t: Tracker, batt: Int?) {
        notify(t.id, KIND_BATT, getString(R.string.notif_battery_crit_title, t.name),
            if (batt != null) getString(R.string.notif_battery_crit_text, batt) else getString(R.string.notif_battery_crit_text_unknown))
    }

    private var dmAuthors: Set<String> = emptySet()

    /** The tags' own DMs (kind 4 from their DM keys): the 10 % warning they send over the radio. */
    @Synchronized
    private fun subscribeTagDms(trackers: List<Tracker>) {
        val authors = trackers.mapNotNull { t -> keysFor(t)?.let { NostrEvent.xonly(it.dmSk).let { pk -> LibreTag.bytesToHex(pk) } } }.toSet()
        if (authors.isEmpty() || authors == dmAuthors) return
        dmAuthors = authors
        relays.subscribe("lt-dm", listOf(buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(4)) })
            put("authors", buildJsonArray { authors.forEach { add(JsonPrimitive(it)) } })
            put("since", System.currentTimeMillis() / 1000 - app.libretag.nostr.TagDm.LOW_BATTERY_TTL_S)
        }))
    }

    private fun onTagDm(ev: NostrEvent) {
        val t = store.trackers.value.firstOrNull { tr -> keysFor(tr)?.let { LibreTag.bytesToHex(NostrEvent.xonly(it.dmSk)) == ev.pubkey } == true } ?: return
        if (!ev.verify()) return
        val keys = keysFor(t) ?: return
        val peer = ev.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)?.let { LibreTag.hexToBytes(it) }?.takeIf { it.size == 32 } ?: return
        // NIP-04: ECDH(dm_sk, owner) = ECDH(owner_sk, dm_pk), so the sender's key opens its own DM
        val text = Nip04.decrypt(keys.dmSk, peer, ev.content) ?: return
        val pct = app.libretag.data.CritBattery.parseDm(text) ?: return
        var fire = false
        store.updateTracker(t.id) { cur -> app.libretag.data.CritBattery.onDm(cur, pct, ev.createdAt * 1000).also { fire = it.notify }.tracker }
        DiagLog.i(TAG, "${t.name}: tag DM, battery $pct %" + if (fire) ", notified" else "")
        val now = store.get(t.id) ?: return
        if (fire && now.alerts.lowBattery) notifyCritical(now, pct)
    }

    // ---------------------------------------------------------------- mesh

    /** when the own node's session last became connected and last went away (ms), for R11 */
    @Volatile private var nodeUpAt = 0L
    @Volatile private var nodeDownAt = 0L

    private fun onNodeState(st: SessionState) {
        val now = System.currentTimeMillis()
        if (st is SessionState.Connected) { if (nodeUpAt <= nodeDownAt || nodeUpAt == 0L) nodeUpAt = now }
        else if (nodeUpAt > nodeDownAt) nodeDownAt = now
        DiagLog.ev("node_state", "state" to st.javaClass.simpleName, "caps" to mesh.session.capabilities.toList().sorted())
    }

    /** the node's server lists `rx_meta`: received packets carry their route type (needed for a node ACK) */
    val nodeRxMeta: Boolean get() = io.github.jooray.meshcore.localapi.Capabilities.RX_META in mesh.session.capabilities

    /** last x values decided on, for the one log line per datagram (P2) */
    private val datagramSeen = object : LinkedHashMap<String, String>(128, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    private suspend fun onDatagram(d: MeshEvent.ChannelData) {
        val now = System.currentTimeMillis()
        lastMeshPacket.value = now
        val at = d.receivedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now
        val x = LibreTag.bytesToHex(d.payload.copyOf(minOf(4, d.payload.size)))
        val path = d.path?.let { LibreTag.bytesToHex(it) }
        DiagLog.ev("mesh_rx", "size" to d.payload.size, "x" to x, "rx_ms" to at, "received_at" to d.receivedAt, "hops" to d.hops,
            "route" to d.routeType, "path" to path, "phs" to d.pathHashSize, "snr" to d.snrDb, "rssi" to d.rssiDbm,
            "channel" to d.channel, "dt" to d.dataType)
        if (d.payload.size == LibreTag.REPORT_P_LEN && store.trackers.value.any { windowFor(it)?.tryOpenExtra(d.payload) != null }) {
            onExtra(d.payload); return
        }
        if (d.payload.size == LibreTag.BLE_P_LEN) { onExtra(d.payload); return }
        if (d.payload.size != LibreTag.REPORT_P_LEN) return
        // R11: a frame the radio held in its offline queue has the drain time, so it is approximate (no ACK,
        // anchor or command). The server says so with `queued` (rx_queued); one without rx_queued is read by
        // the old rule: every frame in the first 20 s after the node connected
        val queued = if (MeshLink.RX_QUEUED in mesh.session.capabilities) d.queued
            else nodeUpAt > 0 && at - nodeUpAt in 0 until NODE_QUEUED_MS
        val earliest = if (queued) (if (nodeDownAt in 1 until at) nodeDownAt else at - NODE_QUEUED_GUESS_MS) else at
        val flood = d.routeType == "flood" || d.routeType == "tflood"
        val direct = d.routeType == "direct" || d.routeType == "tdirect"
        for (t in store.trackers.value) {
            val w = windowFor(t) ?: continue
            val (n, j, r) = w.tryOpen(d.payload, keep = queued) ?: continue
            // heard directly: a hello (only ever sent zero-hop) on a DIRECT route, or a flood with 0 hops (R36)
            val nodeDirect = (direct && j == app.libretag.proto.QuietMesh.J_NEAR) || (flood && (d.path == null || d.path!!.isEmpty()) && d.hops == 0)
            logDatagram(x, "opened by ${t.name} n=$n j=$j")
            DiagLog.ev("open", "tag" to t.id, "via" to "node", "n" to n, "j" to j, "x" to x,
                "rx_ms" to at, "hops" to d.hops, "route" to d.routeType, "path" to path, "snr" to d.snrDb, "rssi" to d.rssiDbm,
                "queued" to queued, "node_direct" to nodeDirect)
            handleReport(t, n, r, d.hops, d.snrDb ?: 0.0, "mesh", if (queued) earliest else at,
                approxUntilMs = if (queued) at else null, j = j, x = x, route = d.routeType, nodeDirect = nodeDirect)
            return
        }
        for (t in store.trackers.value) {
            val w = windowFor(t) ?: continue
            val (n, j, r) = w.trySecond(d.payload) ?: continue
            val nodeDirect = (direct && j == app.libretag.proto.QuietMesh.J_NEAR) || (flood && (d.path == null || d.path!!.isEmpty()) && d.hops == 0)
            logDatagram(x, "duplicate of ${t.name} n=$n j=$j")
            secondSighting(t, n, j, r, via = "mesh", receivedAtMs = at, approx = queued, x = x, nodeDirect = nodeDirect,
                route = d.routeType, hops = d.hops)
            return
        }
        logDatagram(x, "unknown x=$x, kept for resync")
        DiagLog.ev("open_fail", "via" to "node", "size" to d.payload.size, "x" to x)
        rememberUnmatched(d.payload, at, "mesh")
    }

    /** P2: one line per datagram, decided once after trying every tag; a repeat of the same x is not logged again. */
    private fun logDatagram(x: String, what: String) {
        val again = synchronized(datagramSeen) { datagramSeen.put(x, what) == what }
        if (!again) DiagLog.i(TAG, "mesh datagram: $what")
    }

    /**
     * P8: a second copy of report [n] within 120 s (another path or another j): it adds its path to
     * the stored position, and does what the first copy could not (an ACK from the own node or for
     * the bridge that heard it, the own node's command), never a second notification.
     */
    private suspend fun secondSighting(t0: Tracker, n: Long, j: Int, r: Report, via: String, receivedAtMs: Long, approx: Boolean,
                                       x: String, nodeDirect: Boolean, route: String? = null, hops: Int? = null) {
        val t = store.get(t0.id) ?: return
        val pos = t.positions.lastOrNull { it.n == n && it.via != VIA_USB_QUIET } ?: return
        DiagLog.ev("report", "tag" to t.id, "n" to n, "j" to j, "x" to x, "via" to if (via == "mesh") "node" else "relay",
            "rx_time" to receivedAtMs, "age_ms" to System.currentTimeMillis() - receivedAtMs, "approx" to approx,
            "is_new" to false, "second_sighting" to true, "hops" to hops, "route" to route, "sync" to r.sync)
        val newest = n == t.lastN
        store.updateTracker(t.id) { cur ->
            cur.copy(positions = cur.positions.map { p ->
                if (p.n != n || p.via == VIA_USB_QUIET) p
                else p.copy(alsoVia = if (p.via != via) via else p.alsoVia, nodeDirect = p.nodeDirect || nodeDirect,
                    hops = p.hops ?: hops, route = p.route ?: route)
            }, anchor = if (newest && !approx && !pos.approx) app.libretag.data.QuietPlan.anchorAfter(cur.anchor, n, j, gridTime(via, receivedAtMs), r.sync,
                liveAck = false, still = true, dueInGap = false, x = x) { gn, gt -> gridFor(cur, gn, gt) } else cur.anchor)
        }
        val now = store.get(t.id) ?: return
        if (!newest || approx || pos.approx) return
        val fresh = System.currentTimeMillis() - receivedAtMs < CMD_FRESH_MS
        // the ACK the first copy could not get
        val ack = now.lastAck?.takeIf { it.n == n }
        val needNode = via == "mesh" && nodeDirect && ack?.path != "node"
        val needNostr = via == "nostr" && ack == null
        if (needNode || needNostr) {
            ackReport(now, n, j, now.last ?: pos, receivedAtMs, viaNode = via == "mesh", nodeDirect = nodeDirect, x = x)
            // a live ACK went out now: the tag anchors on n, so n is a candidate grid (PROTOCOL.md 2.6)
            val sent = store.get(t.id)?.lastAck?.let { it.n == n && !it.timed } == true
            if (sent && ack?.let { !it.timed } != true) store.updateTracker(t.id) { cur ->
                val before = cur.anchor
                val after = app.libretag.data.QuietPlan.liveAckOnSecond(before, n, j, gridTime(via, receivedAtMs), r.sync) { gn, gt -> gridFor(cur, gn, gt) }
                if (after != before) logAnchor(cur.id, after, 0, "live_ack_second")
                cur.copy(anchor = after)
            }
        }
        // the own node's command the first copy could not send (P8: once)
        val p = now.pending
        if (via == "mesh" && nodeDirect && fresh && p != null && p.sentForN != n) sendCommand(now.id, n, p)
    }

    /** A BLE surroundings packet or a Wi-Fi packet that belongs to a recent report. */
    private fun onExtra(p: ByteArray): Boolean {
        for (t in store.trackers.value) {
            val w = windowFor(t) ?: continue
            val (n, _, x) = w.tryOpenExtra(p) ?: continue
            when (x) {
                is app.libretag.proto.BlePacket -> store.updateTracker(t.id) { cur ->
                    val prev = cur.bleScan?.takeIf { it.n == n }?.devices ?: emptyList()
                    val add = x.devices.map { dv ->
                        app.libretag.data.BleSeenRec(dv.addrHex, when (dv.kind) {
                            app.libretag.proto.BleDevice.ADDR_PUBLIC -> "public"
                            app.libretag.proto.BleDevice.ADDR_STATIC -> "static"
                            app.libretag.proto.BleDevice.ADDR_RPA -> "rpa"
                            else -> "nrpa"
                        }, dv.rssi, dv.name, dv.company, dv.anchor)
                    }
                    cur.copy(bleScan = app.libretag.data.BleScanRec(System.currentTimeMillis(), n,
                        (prev + add).distinctBy { it.address }, maxOf(x.total, (prev + add).size)))
                }
                is app.libretag.proto.WifiPacket -> store.updateTracker(t.id) { cur ->
                    cur.copy(positions = cur.positions.map { pos ->
                        if (pos.n != n) pos else pos.copy(wifi = app.libretag.data.WifiMerge.withPacket(pos.wifi, x))
                    })
                }
            }
            DiagLog.i(TAG, "${t.name}: extra packet for n=$n")
            return true
        }
        return false
    }

    /** History records read over USB (copy j = 2): merged by counter, no alarms for old ones. */
    fun mergeHistory(id: String, records: List<Triple<Long, Int, ByteArray>>): Int {
        val t = store.get(id) ?: return 0
        val chain = chainFor(t) ?: return 0
        val w = TagWindow(chain, t.lastN)
        val have = t.positions.map { it.n }.toSet()
        val quietHave = t.positions.filter { it.via == VIA_USB_QUIET }.map { it.n to it.fixTime }.toSet()
        val add = records.mapNotNull { (n, j, p) ->
            val r = w.openHistory(n, p, j) ?: return@mapNotNull null
            // a wake-up without a report (j = 3) shares n with the next report: keep it beside
            val quiet = j == LibreTag.J_HIST_SILENT
            if (!quiet && n in have) return@mapNotNull null
            if (quiet && ((n to r.fixTime) in quietHave || !r.hasFix)) return@mapNotNull null
            // the record has no receive time: the fix time, else interpolate later by counter
            val fixMs = if (r.fixTime > 0) r.fixTime * 1000 else 0L
            positionOf(n, r, null, 0.0, if (quiet) VIA_USB_QUIET else "usb", fixMs)
        }
        if (add.isEmpty()) return 0
        store.updateTracker(id) { cur ->
            val merged = (cur.positions + add).sortedWith(compareBy({ it.n >= 0 }, { it.n }, { if (it.via == VIA_USB_QUIET) 0 else 1 }))
            // records without a time get the time of their neighbours by counter
            val fixed = fillTimes(merged)
            cur.copy(positions = fixed.takeLast(TrackerStore.MAX_HISTORY), lastN = maxOf(cur.lastN, add.filter { it.via == "usb" }.maxOfOrNull { it.n } ?: -1),
                historyAdded = add.size, lastHistorySync = System.currentTimeMillis())
        }
        forgetWindow(id)
        scope.launch { ensureEstimates(id) }
        return add.size
    }

    private fun fillTimes(list: List<Position>): List<Position> {
        if (list.none { it.receivedAt == 0L }) return list
        val known = list.filter { it.receivedAt > 0 && it.n >= 0 }
        return list.map { p ->
            if (p.receivedAt > 0 || known.isEmpty()) p else {
                val before = known.lastOrNull { it.n < p.n }
                val after = known.firstOrNull { it.n > p.n }
                val t = when {
                    before != null && after != null -> before.receivedAt +
                        (after.receivedAt - before.receivedAt) * (p.n - before.n) / maxOf(1, after.n - before.n)
                    before != null -> before.receivedAt + (p.n - before.n) * 3_600_000L
                    after != null -> after.receivedAt - (after.n - p.n) * 3_600_000L
                    else -> 0L
                }
                p.copy(receivedAt = t)
            }
        }
    }

    private fun positionOf(n: Long, r: Report, hops: Int?, snrDb: Double, via: String, receivedAtMs: Long, approxUntilMs: Long? = null,
                           j: Int = -1, route: String? = null, nodeDirect: Boolean = false): Position {
        val x = ReportExtras.of(r)
        return Position(
            fixTime = r.fixTime, receivedAt = receivedAtMs, hasFix = r.hasFix,
            lat = if (r.hasFix) r.lat else 0.0, lon = if (r.hasFix) r.lon else 0.0, altM = 0,
            battery = r.batteryPct, mode = r.mode.name, hops = hops, snrDb = snrDb, via = via, n = n,
            lastFixTime = r.lastFix?.time ?: 0, lastLat = r.lastFix?.lat ?: 0.0, lastLon = r.lastFix?.lon ?: 0.0,
            neighbours = r.neighbours.map { NeighbourRec(it.hash, it.snrDb) },
            nbTotal = r.nbTotal, nbFromDiscover = r.nbDiscover, haccM = r.haccM,
            cmdAck = r.cmdAck, cmdSeq = r.cmdSeq, lowBatt = r.lowBatt,
            nextInS = x.nextInS, quiet = x.quiet, quietReason = x.quietReason, sync = r.sync,
            energyMah = x.energyMah, bleDevices = x.bleDevices, bleStable = x.bleStable,
            wifi = app.libretag.data.WifiMerge.ofReport(x),
            approxUntil = approxUntilMs?.coerceAtLeast(receivedAtMs),
            boot = r.boot, bootCause = r.bootCause, bootResets = r.bootResets,
            j = j, route = route, nodeDirect = nodeDirect,
        )
    }

    /**
     * The tag runs at least firmware [v] (its console advert, a v2 report): raise the recorded version
     * when it is older or unknown. A USB or Bluetooth session's `version` replaces it with the exact one.
     */
    fun noteFirmwareAtLeast(id: String, v: String) {
        val cur = store.get(id)?.firmware
        if (cur != null && !app.libretag.usb.ConsoleParsers.isOlder(cur, v)) return
        store.updateTracker(id) { t -> if (t.firmware == null || app.libretag.usb.ConsoleParsers.isOlder(t.firmware, v)) t.copy(firmware = v) else t }
    }

    private suspend fun handleReport(
        t: Tracker, n: Long, r: Report, hops: Int?, snrDb: Double, via: String,
        receivedAtMs: Long = System.currentTimeMillis(),
        /** 0.8.1: a bridge drained the report late (tag `approx`), the time lies in [receivedAtMs, approxUntilMs] */
        approxUntilMs: Long? = null,
        /** 0.14.0: the copy opened, its x, the node's route type, and whether the node heard it directly */
        j: Int = -1, x: String? = null, route: String? = null, nodeDirect: Boolean = false,
    ) {
        // a report on air is sealed v2, which only firmware 0.9.0 and newer sends; an unknown version
        // stays unknown (0.9.0 is below MIN_FIRMWARE and would read as too old)
        if (t.firmware != null) noteFirmwareAtLeast(t.id, REPORT_V2_FIRMWARE)
        val pos = positionOf(n, r, hops, snrDb, via, receivedAtMs, approxUntilMs, j, route, nodeDirect)
        val isNew = n > t.lastN
        DiagLog.ev("report", "tag" to t.id, "n" to n, "j" to j, "x" to x, "via" to if (via == "mesh") "node" else "relay",
            "rx_time" to receivedAtMs, "age_ms" to System.currentTimeMillis() - receivedAtMs, "approx" to (approxUntilMs != null),
            "approx_until_ms" to approxUntilMs, "is_new" to isNew, "second_sighting" to false, "hops" to hops, "route" to route,
            "node_direct" to nodeDirect, "sync" to r.sync, "boot" to r.boot, "low" to r.lowBatt, "fix" to r.hasFix,
            "lat" to if (r.hasFix) r.lat else null, "lon" to if (r.hasFix) r.lon else null, "hacc_m" to r.haccM,
            "fix_time" to r.fixTime.takeIf { r.hasFix && it > 0 }, "batt" to r.batteryPct, "mode" to r.mode.name,
            "cmd_ack" to r.cmdAck, "cmd_seq" to r.cmdSeq, "quiet" to r.quiet, "parked" to r.parked, "next_in_s" to r.nextInS, "nb" to r.nbTotal)
        // an approximate copy of a report we already have exactly adds nothing
        if (app.libretag.data.ReportMerge.redundant((store.get(t.id) ?: t).positions, pos)) return
        // 0.12: the counter went back (tag-diagnosis 4.3); a boot report; the phone's clock (4.6)
        val back = !isNew && app.libretag.data.CounterGaps.counterBack((store.get(t.id) ?: t).positions, pos)
        if (via == "mesh" && r.hasFix && r.fixTime > 0 && System.currentTimeMillis() - receivedAtMs < 60_000L) noteClock(receivedAtMs - r.fixTime * 1000 - 5_000L)
        val prevPos = (store.get(t.id) ?: t).last
        // the ACK decision comes first: the anchor needs to know whether a live ACK goes out (R1)
        val ageMs = reportAgeMs(via, receivedAtMs)
        val standing = app.libretag.data.QuietPlan.still(prevPos, pos)
        val choice = app.libretag.data.QuietPlan.ackChoice(isNew, pos.approx, t.canCommand && chainFor(t)?.canCommand == true, j,
            via == "mesh", nodeDirect, nodeRxMeta, mesh.canZeroHop, ageMs, standing)
        val liveAck = choice.way == app.libretag.data.QuietPlan.AckWay.NODE || choice.way == app.libretag.data.QuietPlan.AckWay.NOSTR
        var upgrade = false
        val batt = r.batteryPct
        val low = r.lowBatt || (batt != null && batt <= LOW_BATT_PCT)
        var notifyLow = false
        var notifyCrit = false
        var sendLowDm = false
        var guardAlarm = false
        var zoneLeft: String? = null
        var zoneArrived: String? = null
        var confirmedKind: CommandKind? = null
        var ackedSeq: Int? = null
        var ackedPending: PendingCommand? = null
        var lostReport = false
        var notConfirmedLog: Int? = null
        store.updateTracker(t.id) { cur ->
            var pending = cur.pending
            var lastCommand = cur.lastCommand
            var modeUntil = cur.modeUntil
            var modeIntervalS = cur.modeIntervalS
            var intervalMin = cur.intervalMin
            var reportEvery = cur.reportEvery
            var reportAtOnce = cur.reportAtOnce
            var anchor = cur.anchor
            if (pending != null && app.libretag.data.QuietPlan.confirms(pending, n, isNew, r.cmdAck, r.cmdSeq, r.mode.name)) {
                // R5: CMD_ACK with the command's bits in a report newer than the one it was queued after
                val kind = CommandKind.of(pending.kind)
                confirmedKind = kind
                lastCommand = CommandResult(pending.kind, pending.seq, receivedAtMs, pending.sentAt)
                val body = LibreTag.hexToBytes(pending.body)?.let { Command.decode(it) }
                when (kind) {
                    CommandKind.LOST -> { modeUntil = receivedAtMs + CommandKind.LOST_MIN * 60_000L; modeIntervalS = Status.LOST_INTERVAL_S }
                    CommandKind.TRACK -> { modeUntil = receivedAtMs + (body?.let { Command.modeDurationMin(it) } ?: CommandKind.TRACK_MIN) * 60_000L
                        modeIntervalS = body?.let { Command.modeIntervalS(it) } ?: Status.TRACK_INTERVAL_S }
                    CommandKind.NORMAL -> { modeUntil = null; modeIntervalS = null }
                    CommandKind.INTERVAL -> body?.let { b ->
                        Command.idleIntervalMin(b)?.let { intervalMin = it }
                        Command.idleReportEvery(b)?.let { reportEvery = it }
                        Command.idleAckPatience(b)?.let { reportAtOnce = it == 2 }
                    }
                    else -> {}
                }
                // the send it answered was on one of the grids: that grid is the tag's (R32)
                val q = pending
                val g = q.sentForN
                if (g != null && q.sentForCheck != null) anchor = app.libretag.data.QuietPlan.confirmed(anchor, g)
                ackedSeq = q.seq
                ackedPending = q
                pending = null
            } else if (pending != null && isNew && r.cmdAck && (pending.seq and 0x0F) == r.cmdSeq) {
                notConfirmedLog = pending.seq // the bits match but the counter or the mode does not (R5)
            } else if (pending != null && isNew && n > (pending.sentForN ?: Long.MAX_VALUE)) {
                pending = pending.copy(notConfirmed = true) // retried with this report
            }
            if (r.mode == Mode.IDLE && isNew && confirmedKind == null) { modeUntil = null; modeIntervalS = null }
            notifyLow = low && !cur.lowBattNotified && cur.alerts.lowBattery && isNew
            sendLowDm = low && !cur.lowBattDmSent && isNew && !cur.isShared
            // R16: the guard moves at max(300 m, 3 x accuracy), on exact reports only (the tag's own rule)
            val moved = cur.guardLat != null && cur.guardLon != null && r.hasFix && !pos.approx &&
                Geo.distanceM(cur.guardLat, cur.guardLon, r.lat, r.lon) > app.libretag.proto.QuietMesh.moveM(r.haccM)
            guardAlarm = moved && !cur.guardAlerted && isNew
            // zones: "left Home" when a fix lands outside after being inside
            var inZone = cur.inZone
            var inZoneWifi = cur.inZoneWifi
            val wifiTag = cur.wifiTag || r.wifi != null
            var alarm = cur.alarm
            // 0.9: leaving a place is an event (leftPlace / leftAt), not a latched alarm
            var leftPlace = cur.leftPlace
            var leftAt = cur.leftAt
            if (r.hasFix && isNew && cur.zones.isNotEmpty()) {
                fun d(z: app.libretag.data.Zone) = Geo.distanceM(r.lat, r.lon, z.lat, z.lon)
                // inside at d < r, outside only at d > r + 50 m (hysteresis, SPEC 11.1 review)
                val prev = cur.zones.firstOrNull { it.name == inZone }
                val z = if (prev != null && d(prev) <= prev.radiusM + ZONE_HYST_M) prev
                    else cur.zones.firstOrNull { d(it) <= it.radiusM }
                // left together with the owner's device (the tag says it is quiet because of it): no alarm
                if (z == null && inZone != null && !(r.quiet && r.quietOwner)) {
                    zoneLeft = inZone
                    leftPlace = inZone; leftAt = receivedAtMs
                }
                inZone = z?.name
                inZoneWifi = false
            }
            // Wi-Fi zones (tags without GPS): in while the tag says quiet-in-a-zone, out at its next normal report
            app.libretag.data.WifiZoneTransit.step(inZone, inZoneWifi, r, isNew, wifiTag, app.libretag.data.Status.wifiZoneName(cur), n, cur.lastN)?.let { w ->
                w.left?.let { left ->
                    zoneLeft = left
                    leftPlace = left; leftAt = receivedAtMs
                }
                inZone = w.inZone
                inZoneWifi = w.inZoneWifi
            }
            // at a place again: the departure is over; a new place (not the first report after pairing) is an arrival
            if (inZone != null) { leftPlace = null; leftAt = null }
            if (inZone != null && inZone != cur.inZone && isNew && cur.lastN >= 0) zoneArrived = inZone
            // arming the guard is the consent: it always raises the alarm (no switch since 0.9)
            if (guardAlarm) alarm = app.libretag.data.Places.guardAlarm(receivedAtMs)
            lostReport = r.mode == Mode.LOST && isNew
            upgrade = app.libretag.data.ReportMerge.upgrades(cur, pos)
            // the anchor (PROTOCOL.md 2.6): never on an approximate time, never from an older report (R8)
            if ((isNew || upgrade) && !pos.approx) {
                val gap = prevPos?.let { receivedAtMs - it.receivedAt } ?: 0L
                val before = anchor
                anchor = app.libretag.data.QuietPlan.anchorAfter(anchor, n, j, gridTime(via, receivedAtMs), r.sync, liveAck,
                    still = standing, dueInGap = app.libretag.data.QuietPlan.dueInGap(gap, cur.holdNow, cur.reportPeriodMin * 60L), x = x) { gn, gt ->
                    gridFor(cur.copy(intervalMin = intervalMin), gn, gt)
                }
                if (anchor != before) logAnchor(cur.id, anchor, n - cur.lastN, if (r.sync) "sync" else if (pos.flood) "flood" else if (liveAck) "live_ack" else "report")
            }
            // the last ACK: settled by SYNC in the next report, or by a note and a hello (A2)
            val lastAck = cur.lastAck?.let { a ->
                if (!isNew || a.settled != null || n != a.n + 1) a
                else if (r.sync) a.copy(settled = "sync")
                else if (a.noteAt != null && j == app.libretag.proto.QuietMesh.J_NEAR) a.copy(settled = "next_zero_hop")
                else a.copy(settled = "no")
            }
            val acksNoNote = if (!isNew) cur.acksNoNote else cur.lastAck?.let { a ->
                if (a.path == "nostr" && !a.timed && a.noteAt == null) cur.acksNoNote + 1 else 0 } ?: cur.acksNoNote
            // never "recovered" while the tag still flags LOW_BATT (its threshold is configurable, up to 100 %)
            val recovered = !low && batt != null && batt > LOW_BATT_RESET_PCT
            val crit = app.libretag.data.CritBattery.onReport(cur, batt, isNew, receivedAtMs)
            notifyCrit = crit.notify && cur.alerts.lowBattery
            if (notifyCrit) notifyLow = false // one notification: the stronger one
            crit.tracker.copy(
                lastN = maxOf(cur.lastN, n),
                pending = pending,
                lastCommand = lastCommand,
                modeUntil = modeUntil,
                modeIntervalS = modeIntervalS,
                intervalMin = intervalMin,
                reportEvery = reportEvery,
                reportAtOnce = reportAtOnce,
                anchor = if (intervalMin != cur.intervalMin) regrid(cur.copy(intervalMin = intervalMin), anchor) else anchor,
                lastAck = lastAck,
                acksNoNote = acksNoNote,
                // a copy that is not 11 means the tag dropped its path (or never had one)
                routedVia = if (isNew && j >= 0 && j != app.libretag.proto.QuietMesh.J_ROUTED && !(pos.flood && standing)) null else cur.routedVia,
                routedBridge = if (isNew && j >= 0 && j != app.libretag.proto.QuietMesh.J_ROUTED && !(pos.flood && standing)) null else cur.routedBridge,
                lowBattNotified = if (low || crit.notify) true else if (recovered) false else cur.lowBattNotified,
                lowBattDmSent = if (sendLowDm) true else if (recovered) false else cur.lowBattDmSent,
                guardAlerted = cur.guardAlerted || guardAlarm,
                // R8: an older report does not clear the silent latch
                silentAlerted = if (isNew) false else cur.silentAlerted,
                counterBackAt = if (back) receivedAtMs else cur.counterBackAt,
                bootRecs = bootRecsAfter(cur, pos),
                hereAskAt = if (isNew) null else cur.hereAskAt,
                notHereAt = if (isNew) null else cur.notHereAt,
                dfuStartedAt = if (isNew && cur.dfuStartedAt != null && receivedAtMs > cur.dfuStartedAt) null else cur.dfuStartedAt,
                inZone = inZone,
                inZoneWifi = inZoneWifi,
                wifiTag = wifiTag,
                alarm = alarm,
                leftPlace = leftPlace,
                leftAt = leftAt,
                // late reports (relay copy, a bridge's backlog) slot in by counter
                positions = app.libretag.data.ReportMerge.merge(cur.positions, pos, TrackerStore.MAX_HISTORY),
            )
        }
        notConfirmedLog?.let { DiagLog.ev("cmd_not_confirmed", "tag" to t.id, "seq" to it, "n" to n, "mode" to r.mode.name) }
        val now = store.get(t.id) ?: return
        val name = now.name
        // 0.9: per tag (Tag settings), no global switches; Android's channels are the global control
        if (notifyLow) notify(t.id, KIND_BATT, getString(R.string.notif_battery_title, name),
            if (batt != null) getString(R.string.notif_battery_text, batt) else getString(R.string.notif_battery_text_unknown))
        if (notifyCrit) notifyCritical(now, batt)
        if (sendLowDm) scope.launch { sendOwnerDm(now, getString(R.string.dm_low_battery, name, batt?.toString() ?: "?"), app.libretag.nostr.TagDm.LOW_BATTERY_TTL_S) }
        if (guardAlarm) notify(t.id, KIND_ALARM, getString(R.string.notif_guard_title, name),
            getString(R.string.notif_guard_text, Format.distance(this, GUARD_M), posSummary(pos), nextSummary(now)))
        // leaving and arriving are events on the Places channel, as each place says (Tracker.notifies)
        zoneLeft?.let { z -> if (now.notifies(z, leave = true)) notifyPlace(now, z, leave = true, receivedAtMs, pos) }
        zoneArrived?.let { z -> if (now.notifies(z, leave = false)) notifyPlace(now, z, leave = false, receivedAtMs, pos) }
        // the owner turned Lost on: every Lost report tells (no switch)
        if (lostReport) notify(t.id, KIND_LOST, getString(R.string.notif_lost_title, name),
            getString(R.string.notif_lost_text, posSummary(pos), nextSummary(now)), channel = CH_LOST, onlyOnce = true)
        confirmedKind?.let { _confirmed.tryEmit(t.id to it) }
        ackedPending?.let { q ->
            DiagLog.ev("cmd_confirmed", "tag" to t.id, "kind" to q.kind, "seq" to q.seq, "n" to n, "via" to via, "rx_ms" to receivedAtMs,
                "since_queued_ms" to receivedAtMs - q.queuedAt, "since_send_ms" to q.sentAt?.let { receivedAtMs - it }, "tries" to q.tries)
        }
        // acknowledged: the rest of its mailbox entries would only repeat it on air (at the next
        // reports and at their check times), so they are deleted, not just forgotten
        ackedSeq?.let { seq -> scope.launch { deleteMailbox(t.id, seq) } }
        // Wi-Fi lookup by itself, as Settings say (never a report quiet at one of its places)
        app.libretag.ui.WifiPositioning.autoAfterReport(now, pos, isNew)
        if (isNew) cancel(t.id, KIND_SILENT)
        receipts.value[t.id]?.let { if (it.n <= n) dropReceipt(t.id) }
        bridgeDown.value[t.id]?.let { if (isNew) { bridgeDown.value = bridgeDown.value - t.id } }
        diagContextVersion.value++

        if (!pos.hasFix && pos.neighbours.isNotEmpty()) scope.launch { ensureEstimates(t.id) }
        if (!isNew && !upgrade) return

        // timed ACK entries for older reports are useless once a newer one is in (R28)
        if (isNew) mailboxKeys.obsolete(t.id, ACK_SEQ, n).takeIf { it.isNotEmpty() }?.let { old -> scope.launch { publishDeletions(old) } }
        // the ACK sender (SPEC.md 3.2): own node, relay entry, or timed for the next slots
        if (choice.way == app.libretag.data.QuietPlan.AckWay.SKIP) DiagLog.ev("ack", "tag" to t.id, "n" to n, "skip" to choice.why)

        // Only for a fresh, new report the own node heard directly: zero-hop is the only way it sends
        // (SPEC.md 3.2). Bridges cover the rest through the mailbox.
        val fresh = System.currentTimeMillis() - receivedAtMs < CMD_FRESH_MS
        val p = now.pending?.let { q -> if (isNew) mailboxFallback(now, q, receivedAtMs) else q }
        // entries sealed for an older report can no longer reach the tag (its counter moved on,
        // also by a reboot): delete them before a bridge floods them at their time
        if (p != null && isNew) mailboxKeys.obsolete(t.id, p.seq, n).takeIf { it.isNotEmpty() }?.let { old ->
            scope.launch {
                val done = publishDeletions(old)
                DiagLog.i(TAG, "${now.name}: report n=$n, ${done.size}/${old.size} older mailbox entries deleted")
            }
        }
        if (via == "mesh" && isNew && fresh && !pos.approx && nodeDirect && p != null) scope.launch {
            // the ACK leaves first (MORE is set while a command waits), the command 300 ms later
            delay(ACK_BEFORE_CMD_MS); sendCommand(t.id, n, p)
        }
        // P9: in this order, 150 ms apart: the ACK, the command's entries, then the anchors (PROTOCOL.md 4.3)
        scope.launch {
            if (choice.way != app.libretag.data.QuietPlan.AckWay.SKIP)
                ackReport(now, n, j, pos, receivedAtMs, viaNode = via == "mesh", nodeDirect = nodeDirect, x = x, choice = choice)
            if (p != null) publishMailbox(now, p, timedOnly = !isNew)
            // anchors for the next reports (PROTOCOL.md 4.2), renewed as reports arrive
            if (isNew) maintainAnchors(now)
        }
        if (p != null) checksChanged.trySend(Unit)
    }

    /** The age of a report by its radio time (R17): a relay's created_at is in the bridge's clock, corrected by the offset measured against the relays. */
    private fun reportAgeMs(via: String, receivedAtMs: Long): Long {
        val now = System.currentTimeMillis()
        val off = if (via == "nostr") clockOffMs() else 0L
        return now - off - receivedAtMs
    }

    /** The phone's clock minus the bridges' as applied (R17): the measured offset when above 2 s, else 0. */
    private fun clockOffMs(): Long = relayOffsetMs.takeIf { kotlin.math.abs(it) > CLOCK_APPLY_MS } ?: 0L

    /**
     * A report's time on the bridges' clock, which grids keep (SPEC.md 3.2): a timed entry's `at` is read by a
     * bridge's clock, and a relay copy's created_at is already on it. A time the own node gave (phone clock)
     * moves by the measured offset; own-node slot sends move back by it ([watchSlots]).
     */
    private fun gridTime(via: String, receivedAtMs: Long): Long = if (via == "mesh") receivedAtMs - clockOffMs() else receivedAtMs

    /** One grid of slots anchored on report [n] at [t0] (ms): needs the command chain at n's epoch. */
    fun gridFor(t: Tracker, n: Long, t0: Long): app.libretag.data.CheckPlan? {
        if (n < 0 || t.intervalMin <= 0) return null
        val ce = chainFor(t)?.cmd(LibreTag.epoch(n)) ?: return null
        val ks = app.libretag.proto.QuietMesh.slotKey(ce, n)
        val offs = app.libretag.proto.QuietMesh.slotOffsets(ks, t.intervalMin * 60L, app.libretag.data.QuietPlan.slotCount(t.intervalMin))
        return app.libretag.data.CheckPlan(n, t0, t.intervalMin, offs)
    }

    /** The grids again at a new IDLE interval (a confirmed SET_IDLE): the tag counts its slots with the new base. */
    private fun regrid(t: Tracker, a: app.libretag.data.AnchorState): app.libretag.data.AnchorState {
        fun g(p: app.libretag.data.CheckPlan) = gridFor(t, p.n, p.t0)?.copy(vias = p.vias)
        return a.copy(a = a.a?.let { g(it) }, cands = a.cands.mapNotNull { g(it) })
    }

    private fun logAnchor(id: String, a: app.libretag.data.AnchorState, gap: Long, reason: String) {
        DiagLog.ev("anchor", "tag" to id, "a" to a.a?.let { listOf(it.n, it.t0) }, "cands" to a.cands.map { listOf(it.n, it.t0) },
            "gap" to gap, "reason" to reason, "known" to a.known)
    }

    private fun posSummary(p: Position): String = when {
        p.hasFix -> getString(R.string.pos_gps, p.haccM?.let { Format.distance(this, it.toDouble()) } ?: "?", Format.coord(p.lat, p.lon))
        p.lastFixTime > 0 -> getString(R.string.pos_last_gps, Format.agoLong(this, p.lastFixTime * 1000), Format.coord(p.lastLat, p.lastLon))
        else -> getString(R.string.pos_no_gps)
    }

    private fun nextSummary(t: Tracker): String =
        Status.nextAt(t, System.currentTimeMillis())?.let { getString(R.string.next_report_short, Format.clock(this, it)) } ?: ""

    // ---------------------------------------------------------------- estimates

    private val estimateLock = Mutex()

    /** Area estimates for reports without a fix that don't have one yet (or an outdated one). */
    suspend fun ensureEstimates(id: String) = estimateLock.withLock {
        val t = store.get(id) ?: return@withLock
        val db = repeaters
        val dbAt = db.downloadedAt
        val todo = t.positions.filter { !it.hasFix && it.neighbours.isNotEmpty() && (it.est == null || it.est.dbAt != dbAt) }
            .takeLast(MAX_ESTIMATES_PER_RUN)
        if (todo.isEmpty()) return@withLock
        val results = HashMap<Long, EstimateRec>()
        for (p in todo) results[p.n * 1_000_000_000L + p.receivedAt % 1_000_000_000L] = estimate(t, p)
        store.updateTracker(id) { cur ->
            cur.copy(positions = cur.positions.map { p -> results[p.n * 1_000_000_000L + p.receivedAt % 1_000_000_000L]?.let { p.copy(est = it) } ?: p })
        }
    }

    suspend fun ensureAllEstimates() { store.trackers.value.forEach { ensureEstimates(it.id) } }

    private fun estimate(t: Tracker, p: Position): EstimateRec {
        val db = repeaters
        val dbAt = db.downloadedAt
        if (db.all().isEmpty()) return EstimateRec(state = "nodb", dbAt = dbAt)
        // prior: the fix the report carries, else the newest fix this app has seen from the tag
        val hist = t.positions.lastOrNull { it.hasFix && it.receivedAt <= p.receivedAt }
        val (lastFix, fixS) = when {
            p.lastFixTime > 0 -> (p.lastLat to p.lastLon) to p.lastFixTime
            hist != null -> (hist.lat to hist.lon) to (if (hist.fixTime > 0) hist.fixTime else hist.receivedAt / 1000)
            else -> null to 0L
        }
        val ageS = if (lastFix != null) maxOf(0L, p.receivedAt / 1000 - fixS) else null
        val r = AnchorResolver.resolve(p.neighbours.map { AnchorResolver.Heard(it.hash, it.snrDb) }, { db.byPrefix(it) }, lastFix, ageS)
        // one repeater guessed among look-alikes in other countries is not an estimate
        if (r.ambiguous > 0 && lastFix == null) return EstimateRec(state = "ambiguous", dbAt = dbAt)
        if (r.anchors.isEmpty()) return EstimateRec(state = "none", dbAt = dbAt)
        val e = Estimator.estimate(r.anchors, lastFix, ageS) ?: return EstimateRec(state = "none", dbAt = dbAt)
        return EstimateRec(e.lat, e.lon, e.r68, e.r95, "ok",
            e.anchors.map { AnchorRec(it.rep.name, it.rep.lat, it.rep.lon, it.snrDb, it.rep.key.take(4)) }, dbAt)
    }

    // ---------------------------------------------------------------- commands

    /**
     * 0.11.3: a command meant to go only by the phone's advert or zero-hop missed the wake-up it was
     * expected at (the phone was not near): from this report on the mailbox carries it
     * ([app.libretag.mesh.CommandRoute.mailboxFallback]). Returns the pending command as it is now.
     */
    private fun mailboxFallback(t: Tracker, p: PendingCommand, reportAtMs: Long): PendingCommand {
        if (!app.libretag.mesh.CommandRoute.mailboxFallback(p.mailbox, p.via, p.expectAt, reportAtMs)) return p
        store.updateTracker(t.id) { cur ->
            cur.pending?.takeIf { it.seq == p.seq }?.let { cur.copy(pending = it.copy(mailbox = true,
                via = app.libretag.mesh.CommandRoute.Via.NOSTR.name, expectAt = 0L)) } ?: cur
        }
        DiagLog.i(TAG, "${t.name}: ${p.kind} not taken at the expected wake-up, now through the mailbox")
        return store.get(t.id)?.pending ?: p
    }

    /**
     * Seal the pending command for report n's listen window (k = 0) or slot k of the grid of anchor
     * [n] (sealed from its slot key) and send it from the owner's own node, zero-hop only (SPEC.md
     * 3.2): Pc goes straight to the radio over the Local API, no Nostr event, no bridge. True when the
     * radio took it.
     */
    private suspend fun sendCommand(id: String, n: Long, p: PendingCommand, k: Int = 0, atMs: Long? = null): Boolean {
        val t = store.get(id) ?: return false
        val chain = chainFor(t) ?: return false
        val ce = chain.cmd(LibreTag.epoch(n)) ?: return false
        val cmd = Command.decode(LibreTag.hexToBytes(p.body) ?: return false) ?: return false
        if (!mesh.canZeroHop) {
            DiagLog.i(TAG, "${t.name}: ${p.kind} not sent from our radio (no zero-hop on this Local API server)")
            return false
        }
        val pc = if (k == 0) LibreTag.sealCommand(ce, n, cmd) else app.libretag.proto.QuietMesh.sealSlotCommand(app.libretag.proto.QuietMesh.slotKey(ce, n), k, cmd)
        val r = mesh.sendZeroHopNow(pc)
        DiagLog.ev("cmd_sent", "tag" to t.id, "path" to "node", "route" to "zero-hop", "kind" to p.kind, "seq" to p.seq, "n" to n, "k" to k,
            "xc" to pc.copyOf(4), "at" to atMs, "request_ms" to r.requestMs, "sent_at" to r.sentAtMs, "ok" to r.ok, "error" to r.error)
        if (r.ok) {
            store.updateTracker(id) { it.copy(pending = it.pending?.copy(sentForN = n, sentAt = r.sentAtMs ?: System.currentTimeMillis(),
                sentForCheck = k.takeIf { k > 0 }, tries = (it.pending?.tries ?: 0) + 1)) }
            DiagLog.i(TAG, "${t.name}: sent ${p.kind} for n=$n" + (if (k > 0) " slot $k" else "") + " (zero-hop)")
        }
        return r.ok
    }

    /** When the tag should take a command going by [via]: its next wake-up that scans for BLE, else its next listening one. */
    fun expectedAt(t: Tracker, via: app.libretag.mesh.CommandRoute.Via?, now: Long = System.currentTimeMillis()): Long? {
        val nextReport = Status.nextAt(t, now)
        return if (via == app.libretag.mesh.CommandRoute.Via.BLE)
            app.libretag.mesh.CommandRoute.nextScanAt(Status.checkPlan(t), nextReport, now)
        else Status.of(t, now).nextCheckAt ?: nextReport
    }

    /** The own node heard the tag's last report directly and is connected: a zero-hop send reaches it. */
    fun nodeReaches(t: Tracker): Boolean = mesh.state.value is SessionState.Connected && mesh.canZeroHop &&
        t.last?.let { it.nodeDirect && !it.approx } == true

    fun commandRoute(t: Tracker, kind: CommandKind, now: Long = System.currentTimeMillis()): app.libretag.mesh.CommandRoute.Plan {
        val beaconOk = app.libretag.ble.BleBeaconPlan.eligible(t) && app.libretag.ble.BleCommandBeacon.canAdvertise(this)
        val s = Status.of(t, now)
        val nextReport = Status.nextAt(t, now)
        val window = s.nextCheckAt ?: nextReport
        val scan = app.libretag.mesh.CommandRoute.nextScanAt(Status.checkPlan(t), nextReport, now)
        val near = app.libretag.mesh.CommandRoute.isNear(t.bleSight?.at, t.last?.quietReason,
            t.lastAck?.let { it.path == "node" && it.n == t.lastN } == true, now)
        return app.libretag.mesh.CommandRoute.plan(kind, beaconOk, nodeReaches(t), window, scan, near)
    }

    /** a command was queued or a new report changed the slots: the scheduler looks again */
    private val checksChanged = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Wakes [watchSlots] (the slot alarm, SlotAlarm). */
    fun pokeSlots() { checksChanged.trySend(Unit) }

    /** own-node timed ACKs for a report opened late: tag id -> (report n, grid n, slot k, at ms) */
    private val nodeSlotAcks = java.util.concurrent.ConcurrentHashMap<String, List<NodeSlotAck>>()
    private data class NodeSlotAck(val n: Long, val gridN: Long, val k: Int, val atMs: Long, val hold: Int, val more: Boolean)
    /** (tag id, grid n, k) already sent from the own node */
    private val slotDone = java.util.Collections.synchronizedSet(mutableSetOf<Triple<String, Long, Int>>())

    /**
     * Own-node slot sends (R9, P7): while a command waits for a tag the own node heard directly,
     * send it at t_k + 1 s of each grid's next slot; on not_connected or rate_limited retry every 3 s
     * until t_k + 20 s. Timed ACKs through the node the same way. An alarm ([SlotAlarm]) 5 s ahead
     * wakes this loop while the phone dozes. Bridges cover the rest from the timed entries.
     */
    private suspend fun watchSlots() {
        while (true) {
            val now = System.currentTimeMillis()
            // grids are on the bridges' clock: the own node sends at t_k + 1 s of the phone's (R17)
            val off = clockOffMs()
            var wakeAt = now + CHECK_IDLE_POLL_MS
            for (t in store.trackers.value) {
                if (!t.canCommand) continue
                val p = t.pending
                if (p != null) for (g in Status.sendGrids(t, now)) {
                    for ((i, o) in g.offsetsS.withIndex()) {
                        val k = i + 1
                        val tk = g.t0 + o * 1000 + off
                        val sendAt = tk + app.libretag.proto.QuietMesh.SLOT_SEND_AFTER_S * 1000
                        if (tk + SLOT_RETRY_UNTIL_MS < now) continue
                        val key = Triple(t.id, g.n, k)
                        if (key in slotDone) continue
                        if (sendAt <= now) {
                            if (nodeReaches(t)) {
                                if (sendCommand(t.id, g.n, p, k, sendAt)) slotDone += key
                                else wakeAt = minOf(wakeAt, now + SLOT_RETRY_MS)
                            } else { slotDone += key; DiagLog.ev("tx_skip", "tag" to t.id, "what" to "cmd", "k" to k, "grid" to g.n, "reason" to "node_not_near") }
                        } else wakeAt = minOf(wakeAt, sendAt)
                        break
                    }
                }
                nodeSlotAcks[t.id]?.let { list ->
                    val left = mutableListOf<NodeSlotAck>()
                    for (a in list) {
                        if (store.get(t.id)?.lastN != a.n) continue // a newer report: none after it (R28)
                        val atLocal = a.atMs + off
                        if (atLocal - 1000 + SLOT_RETRY_UNTIL_MS < now) continue
                        if (atLocal > now) { left += a; wakeAt = minOf(wakeAt, atLocal); continue }
                        // MORE as it stands now: a command queued after the ACK was planned keeps the slot open
                        if (!sendNodeAck(t, a.n, a.k, a.hold, a.more || moreFor(t), timed = true, atMs = a.atMs)) { left += a; wakeAt = minOf(wakeAt, now + SLOT_RETRY_MS) }
                    }
                    if (left.isEmpty()) nodeSlotAcks.remove(t.id) else nodeSlotAcks[t.id] = left
                }
            }
            if (slotDone.size > 500) synchronized(slotDone) { slotDone.clear() }
            SlotAlarm.schedule(this, wakeAt)
            val wait = (wakeAt - System.currentTimeMillis()).coerceIn(200L, CHECK_IDLE_POLL_MS)
            kotlinx.coroutines.withTimeoutOrNull(wait) { checksChanged.receive() }
        }
    }

    /**
     * Queue a command. It goes out (1) from the own node zero-hop right after the tag's next report
     * or at its slots, when the node hears the tag directly, and (2) through the Nostr mailbox:
     * untimed for the next counters, a late entry for the report that just came, and timed for the
     * next slots on every grid the app holds (PROTOCOL.md 4, 4.3). [blind]: Lost behind the owner's
     * confirmation, also flooded blind by a named bridge (4.3).
     */
    fun queueCommand(id: String, kind: CommandKind, arg: Int = 0, checkS: Int = 0, reportEvery: Int = 0, durationMin: Int = 0,
                     ackPatience: Int = 0, blind: Boolean = false) {
        var queued: PendingCommand? = null
        var replaced: PendingCommand? = null
        val now = System.currentTimeMillis()
        store.updateTracker(id) { t ->
            replaced = t.pending
            // R6: random, its low bits unlike the tag's last executed command and our own previous one
            val seq = app.libretag.data.QuietPlan.pickSeq(t.last?.cmdSeq, t.cmdSeq) { random.nextInt(it) }
            val route = commandRoute(t, kind, now)
            val p = PendingCommand(seq, kind.name.lowercase(), LibreTag.bytesToHex(kind.build(seq, arg, checkS, reportEvery, durationMin, ackPatience).encode()),
                kind = kind.name, queuedAt = now, group = app.libretag.nostr.MailboxPlan.newGroup(random),
                mailbox = route.mailbox, via = if (app.libretag.mesh.CommandRoute.quietLocally(kind)) route.via.name else "",
                expectAt = route.at ?: 0L, queuedAtN = t.lastN, expiresAt = now + kind.mailboxTtlS * 1000)
            queued = p
            t.copy(cmdSeq = seq, pending = p)
        }
        val p = queued ?: return
        DiagLog.ev("cmd_queued", "tag" to id, "kind" to p.kind, "seq" to p.seq, "queued_at_n" to p.queuedAtN, "lifetime_s" to kind.mailboxTtlS,
            "mailbox" to p.mailbox, "local_via" to p.via, "expect_at" to p.expectAt.takeIf { it > 0 }, "replaced" to replaced?.seq, "blind" to blind)
        replaced?.let { old -> DiagLog.ev("cmd_ended", "tag" to id, "seq" to old.seq, "why" to "replaced", "tries" to old.tries) }
        // the new command replaces the old one: bridges must not deliver the old one any more
        replaced?.let { old -> scope.launch { deleteMailbox(id, old.seq) } }
        val t = store.get(id) ?: return
        scope.launch { publishMailbox(t, p, blind = blind); maintainAnchors(t); timedAcksWithMore(id) }
        checksChanged.trySend(Unit)
    }

    /**
     * Drop the pending command and delete its mailbox events (NIP-09, signed by each event's own
     * key), so bridges do not transmit it. The UI offers it only while the command waits (not sent
     * by our radio, no bridge claim seen).
     */
    fun cancelCommand(id: String) {
        var old: PendingCommand? = null
        store.updateTracker(id) { old = it.pending; it.copy(pending = null) }
        old?.let { p ->
            DiagLog.ev("cmd_ended", "tag" to id, "seq" to p.seq, "why" to "cancelled", "tries" to p.tries)
            scope.launch { deleteMailbox(id, p.seq) }
        }
    }

    /**
     * Every 30 s: a command whose lifetime ran out ends with a notice (R7); one no relay accepted
     * yet goes out again (R10); Lost or Track that ran out is told once (R15).
     */
    private suspend fun watchCommands() {
        var first = true
        var lastTick = System.currentTimeMillis()
        var lastClock = 0L
        while (true) {
            delay(if (first) 5_000 else COMMAND_LOOP_MS)
            val now = System.currentTimeMillis()
            if (now - lastTick > DOZE_GAP_MS) DiagLog.ev("doze", "gap_ms" to now - lastTick)
            lastTick = now
            if (now - lastClock > 3600_000L) { lastClock = now; DiagLog.ev("clock", "offset_ms" to relayOffsetMs.takeIf { relaySamples.isNotEmpty() }) }
            for (t in store.trackers.value) {
                val p = t.pending
                if (p != null && app.libretag.data.QuietPlan.expired(p, now)) { expireCommand(t, p); continue }
                // R10: republished at start, and every 30 s while no relay took any of its entries
                if (p != null && p.mailbox && t.canCommand && relays.connected.value.first > 0 && (first || p.accepted == 0)) publishMailbox(t, p)
                app.libretag.data.QuietPlan.modeEnded(t, now)?.let { until ->
                    store.updateTracker(t.id) { it.copy(modeEndNotified = until) }
                    val k = if (t.last?.mode == "LOST") R.string.notif_lost_ended_title else R.string.notif_track_ended_title
                    noteNotify(t.id)
                    notify(t.id, KIND_MODE_END, getString(k, t.name), getString(R.string.notif_mode_ended_text), alarm = false)
                    DiagLog.ev("notify", "tag" to t.id, "kind" to "mode_ended", "value" to until)
                }
            }
            first = false
        }
    }

    /** R7: the command's lifetime is over: delete its entries and tell the owner once. */
    private suspend fun expireCommand(t: Tracker, p: PendingCommand) {
        store.updateTracker(t.id) { cur -> if (cur.pending?.seq == p.seq) cur.copy(pending = null, expiredNotified = p.seq) else cur }
        DiagLog.ev("cmd_ended", "tag" to t.id, "seq" to p.seq, "why" to "expired", "tries" to p.tries, "sent" to (p.sentAt != null))
        deleteMailbox(t.id, p.seq)
        val k = CommandKind.of(p.kind) ?: return
        val why = when {
            p.missAt != null -> getString(R.string.notif_not_delivered_miss, Format.clock(this, p.missAt))
            p.sentAt != null -> getString(R.string.notif_not_delivered_sent)
            else -> getString(R.string.notif_not_delivered_unheard, t.name)
        }
        notify(t.id, KIND_NOT_DELIVERED, getString(R.string.notif_not_delivered_title, app.libretag.ui.TagText.cmdName(this, k), t.name), why, alarm = false)
        DiagLog.ev("notify", "tag" to t.id, "kind" to "not_delivered", "value" to p.seq)
    }

    /** Publish the deletions of command (id, seq); keys whose deletion no relay took stay for [retryDeletions]. */
    private suspend fun deleteMailbox(id: String, seq: Int) {
        val keys = mailboxKeys.cancel(id, seq)
        if (keys.isEmpty()) return
        val done = publishDeletions(keys)
        DiagLog.i(TAG, "cancel seq=$seq: ${done.size}/${keys.size} mailbox deletions accepted")
    }

    private suspend fun publishDeletions(keys: List<app.libretag.nostr.MailboxKey>): Set<String> {
        val nowS = System.currentTimeMillis() / 1000
        val done = kotlinx.coroutines.coroutineScope {
            keys.map { k ->
                async {
                    val ev = if (k.exp > nowS) app.libretag.nostr.MailboxKeys.deletion(k, nowS, LibreTag.NOSTR_COMMAND_KIND,
                        mailboxSkFor(k.tracker, k.n)) else null
                    k.id.takeIf { ev == null || relays.publish(ev.toJson()) } // expired or broken key: nothing to delete
                }
            }.awaitAll().filterNotNull().toSet()
        }
        keys.groupBy { it.tracker to it.seq }.forEach { (ts, ks) ->
            mailboxKeys.remove(ts.first, ts.second, ks.map { it.id }.filter { it in done }.toSet())
        }
        return done
    }

    /**
     * Deletions that found no relay (offline when cancelled) go out again once a minute; every
     * 10 minutes the anchors of every tag are looked at ([maintainAnchors]).
     */
    private suspend fun retryDeletions() {
        var round = 0
        while (true) {
            delay(60_000)
            val nowS = System.currentTimeMillis() / 1000
            mailboxKeys.prune(nowS)
            subscribeClaims()
            val todo = mailboxKeys.toDelete(nowS)
            if (todo.isNotEmpty() && relays.connected.value.first > 0) publishDeletions(todo)
            if (round++ % 10 == 0) for (t in store.trackers.value) maintainAnchors(t)
        }
    }

    /** sk_n of report [n] of tag [trackerId] (PROTOCOL.md 4.2), or null (no command chain, tag removed). */
    private fun mailboxSkFor(trackerId: String, n: Long): ByteArray? {
        if (n < 0) return null
        val t = store.get(trackerId) ?: return null
        val ce = chainFor(t)?.cmd(LibreTag.epoch(n)) ?: return null
        return LibreTag.mailboxSk(ce, n)
    }

    private fun keepKey(t: Tracker, seq: Int, ev: NostrEvent, exp: Long, nowS: Long, n: Long) {
        val ys = ev.tags.filter { it.size >= 2 && it[0] == "y" }.map { it[1] }
        mailboxKeys.add(app.libretag.nostr.MailboxKey(t.id, seq, ev.id, "", exp, ys = ys, n = n), nowS)
        subscribeClaims()
    }

    private val anchorLock = Mutex()

    /**
     * Keep anchors (PROTOCOL.md 4.2, 4.3) published for the next reports of a tag we can command,
     * whether a command waits or not: 16 ahead in IDLE, 24 in TRACK and LOST, the two counters a
     * reboot lands on and the next epoch, five y each, expiring with them (up to 50 h). Missing ones
     * and those past half their life go out, named for the bridges that heard the tag (open when
     * none is known), created_at spread over 5 minutes, 150 ms apart (P9).
     */
    private suspend fun maintainAnchors(tIn: Tracker) = anchorLock.withLock {
        val t = store.get(tIn.id) ?: return@withLock
        if (!t.canCommand || relays.connected.value.first == 0) return@withLock
        val chain = chainFor(t) ?: return@withLock
        val nowS = System.currentTimeMillis() / 1000
        val moving = Status.modeNow(t, nowS * 1000) != "IDLE"
        val ahead = if (moving) app.libretag.nostr.AnchorPlan.AHEAD_MOVING else app.libretag.nostr.AnchorPlan.AHEAD
        val period = if (moving) Status.intervalS(t, t.last, nowS * 1000).toLong() else t.reportPeriodMin * 60L
        val ttl = app.libretag.nostr.AnchorPlan.ttlS(period, ahead)
        val due = app.libretag.nostr.AnchorPlan.due(app.libretag.nostr.AnchorPlan.wanted(t.lastN, moving),
            anchorBook.published(t.id, nowS), nowS, ttl)
        if (due.isEmpty()) return@withLock
        val targets = runCatching { bridgeView(t, chain, null).untimed(nowS) }.getOrDefault(app.libretag.nostr.Targets.OPEN)
        var ok = 0
        for (n in due) {
            val e = LibreTag.epoch(n)
            val ce = chain.cmd(e) ?: continue
            val ve = chain.view(e) ?: continue
            val exp = nowS + ttl
            val ev = app.libretag.nostr.AnchorPlan.event(ce, ve, n, nowS - random.nextInt(app.libretag.nostr.AnchorPlan.SPREAD_S), exp, targets)
            if (relays.publish(ev.toJson())) { anchorBook.record(t.id, n, exp, nowS); ok++ }
            delay(PUBLISH_GAP_MS)
        }
        DiagLog.i(TAG, "${t.name}: $ok/${due.size} anchors published (n ${due.first()}..${due.last()}, ${ttl / 3600} h)")
    }

    private var claimYs: Set<String> = emptySet()
    private var claimGs: Set<String> = emptySet()

    /** Bridge claims and notes (kind 24244) for the tags of our pending entries, and miss notes by group. */
    @Synchronized
    private fun subscribeClaims() {
        val nowS = System.currentTimeMillis() / 1000
        val ys = mailboxKeys.pendingYs(nowS)
        val gs = store.trackers.value.mapNotNull { it.pending?.group?.takeIf { g -> g.isNotEmpty() } }.toSet()
        if ((ys.isEmpty() && gs.isEmpty()) || (ys == claimYs && gs == claimGs)) return
        claimYs = ys
        claimGs = gs
        relays.subscribe("lt-claims", listOfNotNull(
            ys.takeIf { it.isNotEmpty() }?.let { y -> buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(app.libretag.nostr.MailboxKeys.KIND_CLAIM)) })
                put("#y", buildJsonArray { y.forEach { add(JsonPrimitive(it)) } })
                put("since", nowS - 60)
            } },
            gs.takeIf { it.isNotEmpty() }?.let { g -> buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(app.libretag.nostr.MailboxKeys.KIND_CLAIM)) })
                put("#g", buildJsonArray { g.forEach { add(JsonPrimitive(it)) } })
                put("since", nowS - 60)
            } },
        ))
    }

    /**
     * Kind 24244 from a bridge (PROTOCOL.md 4, 4.3): a claim for one of our pending commands (it is
     * about to transmit it: shown as sent), an "ack sent" note for one of our ACK entries, or a miss
     * note for a timed entry it could not send ("Not sent at hh:mm").
     */
    private fun onClaim(ev: NostrEvent) {
        val nowS = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(nowS - ev.createdAt) > CLAIM_MAX_AGE_S) return
        fun tag(k: String) = ev.tags.firstOrNull { it.size >= 2 && it[0] == k }?.get(1)
        tag("miss")?.let { why -> onMissNote(ev, why); return }
        val y = tag("y") ?: return
        val (id, seq) = mailboxKeys.commandFor(y) ?: return
        if (!ev.verify()) return
        if (tag("ack") != null || seq == ACK_SEQ) { if (tag("ack") != null) scope.launch { if (bridges.isBridge(ev.pubkey)) onAckNote(id, ev, y, tag("ack")!!) }; return }
        // 4.1: only a claim signed by an announced bridge key counts (random keys are anyone's)
        scope.launch { if (bridges.isBridge(ev.pubkey)) markClaimed(id, seq, ev, nowS) }
    }

    private fun markClaimed(id: String, seq: Int, ev: NostrEvent, nowS: Long) {
        var marked = false
        store.updateTracker(id) { t ->
            val p = t.pending
            if (p == null || p.seq != seq || p.sentAt != null) t
            else { marked = true; t.copy(pending = p.copy(sentAt = minOf(ev.createdAt, nowS) * 1000, tries = p.tries + 1)) }
        }
        if (marked) DiagLog.i(TAG, "bridge claim for seq=$seq: shown as sent")
        DiagLog.ev("claim_seen", "tag" to id, "seq" to seq, "id" to ev.id, "pubkey" to ev.pubkey, "created_at" to ev.createdAt,
            "hops" to ev.tags.firstOrNull { it.size >= 2 && it[0] == "hops" }?.get(1), "first" to marked)
    }

    /** "ack sent" (PROTOCOL.md 4.3): the bridge transmitted our ACK; an ["ack","2"] author is the p of the next routed entries. */
    private fun onAckNote(id: String, ev: NostrEvent, y: String, form: String) {
        // a routed note names the path record it used (["via", x], PROTOCOL.md 4.3): the next routed entries name it.
        // Its y is no path record for a timed ACK (y is the xa there), so only via counts
        val via = ev.tags.firstOrNull { it.size >= 2 && it[0] == "via" }?.get(1)?.takeIf { app.libretag.nostr.isLowerHex(it, 4) }
        store.updateTracker(id) { t ->
            val a = t.lastAck
            t.copy(lastAck = a?.let { if (it.noteAt == null) it.copy(noteAt = ev.createdAt * 1000) else it }, acksNoNote = 0,
                routedVia = if (form == "2" && via != null) via else t.routedVia, routedBridge = if (form == "2") ev.pubkey else t.routedBridge)
        }
        DiagLog.ev("ack_note", "tag" to id, "y" to y, "ack" to form, "via" to via, "pubkey" to ev.pubkey, "at" to ev.tags.firstOrNull { it.size >= 2 && it[0] == "at" }?.get(1),
            "note_ms" to store.get(id)?.lastAck?.let { ev.createdAt * 1000 - it.at })
    }

    /** A miss note: a bridge could not send a timed entry of one of our commands (R20). */
    private fun onMissNote(ev: NostrEvent, why: String) {
        val e = ev.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        val key = mailboxKeys.all().firstOrNull { it.id == e } ?: return
        if (!ev.verify()) return
        val atS = ev.tags.firstOrNull { it.size >= 2 && it[0] == "at" }?.get(1)?.toLongOrNull() ?: ev.createdAt
        scope.launch {
            if (!bridges.isBridge(ev.pubkey)) return@launch
            store.updateTracker(key.tracker) { t ->
                val p = t.pending
                if (p == null || p.seq != key.seq) t else t.copy(pending = p.copy(missAt = atS * 1000, missWhy = why.take(16)))
            }
            DiagLog.ev("miss_seen", "tag" to key.tracker, "seq" to key.seq, "id" to e, "pubkey" to ev.pubkey, "at" to atS, "reason" to why)
        }
    }

    /**
     * One mailbox entry: untimed ([atS] null, waits for report n), late (untimed for report n that
     * already came, PROTOCOL.md 4.2) or timed (slot k of the grid of anchor n). Signed with sk_n.
     */
    private class MailboxEntry(val n: Long, val tags: List<List<String>>, val pc: ByteArray, val atS: Long?, val ttlS: Long,
        val targets: app.libretag.nostr.Targets = app.libretag.nostr.Targets.OPEN, val late: Boolean = false, val ack: Boolean = false,
        val k: Int = 0, val grid: Boolean = false)

    /** Receipts and announcements (PROTOCOL.md 4.1): which bridges to name in format 2 entries. */
    private val bridges by lazy { app.libretag.nostr.BridgeLookup({ f, ms -> relays.query(f, ms) }) }

    // ---------------------------------------------------------------- 0.12: receipts ahead (tag-diagnosis 4.4)

    /** the tag whose detail is open: its relays are asked every 15 min instead of every hour while it is late */
    @Volatile var detailOpenId: String? = null
    private val aheadAt = mutableMapOf<String, Long>()
    private val aheadLock = Mutex()

    /** A late tag: ask the relays once for receipts and reports of the counters the app has not opened. */
    fun maybeAskAhead(t: Tracker, now: Long, force: Boolean = false) {
        if (t.lastN < 0) return
        val every = if (t.id == detailOpenId) AHEAD_DETAIL_MS else AHEAD_EVERY_MS
        synchronized(aheadAt) {
            if (!force && now - (aheadAt[t.id] ?: 0L) < every) return
            aheadAt[t.id] = now
        }
        scope.launch { aheadLock.withLock { runCatching { askAhead(t.id) }.onFailure { DiagLog.w(TAG, "receipts ahead: ${it.message}") } } }
    }

    private suspend fun askAhead(id: String) {
        val t = store.get(id) ?: return
        val chain = chainFor(t) ?: return
        if (relays.connected.value.first == 0) return
        val yOf = { n: Long, j: Int -> chain.view(LibreTag.epoch(n))?.let { LibreTag.bytesToHex(LibreTag.reportTag(it, n, j)) } }
        val ys = app.libretag.nostr.BridgeTargets.nextYs(t.lastN, yOf)
        val res = bridges.ahead(ys.keys)
        // relays that were unreachable when the report was live may have it now
        for (e in res.reports) onReportEvent(e)
        val cur = store.get(id) ?: return
        val nowS = System.currentTimeMillis() / 1000
        val hit = app.libretag.nostr.BridgeTargets.ahead(res.receipts, res.announcements, ys, nowS)?.takeIf { it.n > cur.lastN }
        if (hit == null) { dropReceipt(id); return }
        // the report itself from the relays the bridge publishes to
        val relaysOf = hit.announcement.relays
        if (relaysOf.isNotEmpty()) {
            val want = ys.filterValues { it == hit.n }.keys
            val got = app.libretag.nostr.RelayOnce.query(relaysOf, listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(LibreTag.NOSTR_REPORT_KIND)) })
                put("#y", buildJsonArray { want.forEach { add(JsonPrimitive(it)) } })
            })).events
            for (e in got) onReportEvent(e)
        }
        val after = store.get(id) ?: return
        if (after.lastN >= hit.n) { dropReceipt(id); return }
        // "Add relay" only for a bridge with receipts for this tag on two different days (owner decision 8)
        val recent = app.libretag.nostr.BridgeTargets.recentYs(after.lastN, null, yOf, count = 48)
        val view = bridges.view(recent)
        val days = app.libretag.nostr.BridgeTargets.receiptDays(view.receipts + hit.receipt, hit.receipt.pubkey, recent.keys + hit.receipt.y)
        val mine = store.relays.map { it.trimEnd('/') }.toSet()
        val relay = relaysOf.firstOrNull { it.trimEnd('/') !in mine }
        DiagLog.i(TAG, "${after.name}: a bridge heard n=${hit.n} (${hit.receipt.hops}), not on our relays; receipts on $days day(s)")
        receipts.value = receipts.value + (id to app.libretag.data.ReceiptSeen(hit.receipt.heardAt * 1000, hit.n, hit.receipt.hops,
            relay, trusted = days >= 2, fetchFailed = true))
        diagContextVersion.value++
    }

    private fun dropReceipt(id: String) {
        if (id in receipts.value) { receipts.value = receipts.value - id; diagContextVersion.value++ }
    }

    /** "Add relay": the bridge's relay joins the relays the app reads (after the owner confirmed the URL). */
    fun addRelay(url: String) {
        val list = (store.relays + url).distinctBy { it.trimEnd('/') }
        store.relays = list
        relays.setRelays(list, list)
        store.trackers.value.forEach { t -> receipts.value[t.id]?.let { scope.launch { delay(5_000); maybeAskAhead(t, System.currentTimeMillis(), force = true) } } }
    }

    /** "Try again": reconnect every relay, then the backfill query. */
    fun retryRelays() {
        val list = store.relays
        relays.setRelays(emptyList(), emptyList())
        relays.setRelays(list, list)
        scope.launch { quickSync() }
    }

    /** The bridges that heard the tag's last reports (and report [extraN]); empty (open entries) on any failure. */
    private suspend fun bridgeView(t: Tracker, chain: KeyChain, extraN: Long?, timeoutMs: Long = app.libretag.nostr.BridgeLookup.QUERY_TIMEOUT_MS): app.libretag.nostr.BridgeLookup.View {
        val recent = app.libretag.nostr.BridgeTargets.recentYs(t.lastN, extraN, { n, j ->
            chain.view(LibreTag.epoch(n))?.let { LibreTag.bytesToHex(LibreTag.reportTag(it, n, j)) }
        })
        return bridges.view(recent, timeoutMs)
    }

    /** one command's entries at a time */
    private val publishLock = Mutex()

    /** The route a command entry asks for (PROTOCOL.md 4.3): `both` for Lost and Track, else `flood`. */
    private fun routeOf(kind: CommandKind?, later: Boolean = false): String =
        if (kind == CommandKind.LOST || kind == CommandKind.TRACK || later) "both" else "flood"

    /**
     * Queue the whole command into the mailbox (PROTOCOL.md 4, 4.3): a late entry for the report
     * that just came while the tag still listens (under 40 s, P6: no receipt wait), untimed entries
     * for the next [QuietMesh.UNTIMED_AHEAD] counters (five y each), and timed entries for the next
     * slots on every grid the app holds ([timedEntries]). A ROUTED tag gets `via` on every entry.
     */
    private suspend fun publishMailbox(t0: Tracker, p: PendingCommand, timedOnly: Boolean = false, blind: Boolean = false) {
        if (!p.mailbox) return // only local ways (CommandRoute): nothing for the bridges to flood
        val t = store.get(t0.id) ?: t0
        val chain = chainFor(t) ?: return
        val cmd = Command.decode(LibreTag.hexToBytes(p.body) ?: return) ?: return
        val kind = CommandKind.of(p.kind)
        val lost = kind == CommandKind.LOST
        val ttl = ((if (p.expiresAt > 0) p.expiresAt else p.queuedAt + (kind?.mailboxTtlS ?: 172_800L) * 1000) - System.currentTimeMillis()) / 1000
        if (ttl <= 0) return
        val entries = mutableListOf<MailboxEntry>()
        val routed = t.routedVia?.takeIf { app.libretag.data.QuietPlan.link(t) == app.libretag.data.QuietPlan.Link.ROUTED }
        val last = t.last
        fun ysOf(n: Long): List<List<String>>? {
            val ve = chain.view(LibreTag.epoch(n)) ?: return null
            return app.libretag.proto.QuietMesh.J_ON_AIR.map { j -> listOf("y", LibreTag.bytesToHex(LibreTag.reportTag(ve, n, j))) }
        }
        // a late entry: the tag still listens after its last report, a bridge that heard it sends at once
        val lateOk = t.lastN >= 0 && last != null && last.n == t.lastN && !last.approx &&
            app.libretag.nostr.MailboxPlan.lateOk(reportAgeMs(last.via, last.receivedAt) / 1000)
        val quick = if (lost) app.libretag.nostr.Targets.OPEN
            else bridges.cachedView()?.untimed(System.currentTimeMillis() / 1000) ?: app.libretag.nostr.Targets.OPEN
        if (!timedOnly && lateOk) {
            val n = t.lastN
            val ce = chain.cmd(LibreTag.epoch(n))
            val ys = ysOf(n)
            if (ce != null && ys != null) entries += MailboxEntry(n, ys + app.libretag.nostr.BridgeTargets.entryTags(quick, routeOf(kind)) +
                listOfNotNull(routed?.let { listOf("via", it) }), LibreTag.sealCommand(ce, n, cmd), null, ttl, quick, late = true)
        }
        if (entries.isNotEmpty()) publishEntries(t, p.seq, entries.toList())
        entries.clear()
        val view = bridgeView(t, chain, Status.checkPlan(t)?.n)
        // 4.1: name the bridges that heard the tag; a lost tag may be anywhere, so its entries stay open
        val untimed = if (lost) app.libretag.nostr.Targets.OPEN else view.untimed(System.currentTimeMillis() / 1000)
        if (!timedOnly) for (k in 1..app.libretag.proto.QuietMesh.UNTIMED_AHEAD) {
            val n = maxOf(t.lastN, -1) + k
            if (n < 0) continue
            val ce = chain.cmd(LibreTag.epoch(n)) ?: continue
            val ys = ysOf(n) ?: continue
            entries += MailboxEntry(n, ys + app.libretag.nostr.BridgeTargets.entryTags(untimed, routeOf(kind)) +
                listOfNotNull(routed?.let { listOf("via", it) }), LibreTag.sealCommand(ce, n, cmd), null, ttl, untimed)
        }
        entries += timedEntries(t, chain, cmd, kind, view, System.currentTimeMillis() / 1000, p.expiresAt / 1000, blind)
        publishEntries(t, p.seq, entries)
    }

    /**
     * Timed entries (PROTOCOL.md 2.6, 4): for every grid the app holds, the next 3 slots, then every
     * 3rd until the command's lifetime (LOST every slot for its first 6 h), at most 12 per grid; each
     * sealed for its own (n_a, k) from the slot key, with ["at", t_k + 1], a ["via"] per copy of
     * the anchor the app has, the first slot `flood` and the later ones `both` (a slot that came
     * unconfirmed), and ["fb", "0"] on all but the latest grid's send of the same try except for
     * Lost and Track (R3, R35). [blind]: Lost also as blind entries for the named bridge (4.3).
     */
    private fun timedEntries(t: Tracker, chain: KeyChain, cmd: Command, kind: CommandKind?, view: app.libretag.nostr.BridgeLookup.View,
                             nowS: Long, untilS: Long, blind: Boolean): List<MailboxEntry> {
        val grids = Status.sendGrids(t, nowS * 1000)
        if (grids.isEmpty()) return emptyList()
        val lostOrTrack = kind == CommandKind.LOST || kind == CommandKind.TRACK
        val perGrid = grids.map { g ->
            val offs = g.offsetsS
            val fromK = offs.indexOfFirst { g.t0 / 1000 + it + 1 > nowS + 5 } + 1
            if (fromK <= 0) g to emptyList() else g to app.libretag.proto.QuietMesh.commandSlots(offs, fromK, untilS - g.t0 / 1000,
                kind == CommandKind.LOST, max = MAILBOX_CHECKS)
        }
        val out = mutableListOf<MailboxEntry>()
        val tries = perGrid.maxOf { it.second.size }
        for (i in 0 until tries) {
            val sends = perGrid.mapNotNull { (g, ks) -> ks.getOrNull(i)?.let { g to it } }
            val fbs = app.libretag.proto.QuietMesh.pairFallback(sends.map { (g, k) -> g.t0 / 1000 + g.offsetsS[k - 1] }, lostOrTrack)
            sends.forEachIndexed { si, (g, k) ->
                val ce = chain.cmd(LibreTag.epoch(g.n)) ?: return@forEachIndexed
                val ks = app.libretag.proto.QuietMesh.slotKey(ce, g.n)
                val atS = app.libretag.proto.QuietMesh.slotSendAt(g.t0 / 1000, g.offsetsS[k - 1])
                val vias = g.vias.ifEmpty { listOfNotNull(chain.view(LibreTag.epoch(g.n))?.let { LibreTag.bytesToHex(LibreTag.reportTag(it, g.n, LibreTag.J_HOME)) }) }
                val targets = if (kind == CommandKind.LOST) app.libretag.nostr.Targets.OPEN else vias.firstOrNull()?.let { view.timed(it, nowS) } ?: app.libretag.nostr.Targets.OPEN
                val tags = listOf(listOf("y", LibreTag.bytesToHex(app.libretag.proto.QuietMesh.slotCommandTag(ks, k)))) +
                    app.libretag.nostr.BridgeTargets.entryTags(targets, routeOf(kind, later = i > 0)) +
                    listOf(listOf("at", atS.toString())) + vias.map { listOf("via", it) } +
                    listOfNotNull(listOf("fb", "0").takeIf { !fbs[si] })
                out += MailboxEntry(g.n, tags, app.libretag.proto.QuietMesh.sealSlotCommand(ks, k, cmd), atS, CHECK_SLOT_TTL_S, targets, k = k, grid = true)
            }
        }
        // blind Lost (behind the owner's confirmation): a named bridge floods it at three slots without having heard via
        if (blind && kind == CommandKind.LOST) {
            val bridge = bridges.anyKnownBridge()
            val g = grids.first()
            if (bridge != null) {
                val ce = chain.cmd(LibreTag.epoch(g.n))
                if (ce != null) {
                    val ks = app.libretag.proto.QuietMesh.slotKey(ce, g.n)
                    g.offsetsS.withIndex().filter { g.t0 / 1000 + it.value + 1 > nowS + 5 }.take(BLIND_TRIES).forEach { (i, off) ->
                        val k = i + 1
                        val atS = app.libretag.proto.QuietMesh.slotSendAt(g.t0 / 1000, off)
                        val tags = listOf(listOf("y", LibreTag.bytesToHex(app.libretag.proto.QuietMesh.slotCommandTag(ks, k))),
                            listOf("v", "2"), listOf("route", "flood"), listOf("p", bridge), listOf("blind", "1"), listOf("at", atS.toString()))
                        out += MailboxEntry(g.n, tags, app.libretag.proto.QuietMesh.sealSlotCommand(ks, k, cmd), atS, CHECK_SLOT_TTL_S, k = k, grid = true)
                    }
                }
            } else DiagLog.i(TAG, "${t.name}: blind Lost asked, but no bridge is known to name")
        }
        return out
    }

    /** The command's group tag (16 lowercase hex, random per command), made once and kept with it. */
    private fun groupOf(id: String, seq: Int): String? {
        var g: String? = null
        store.updateTracker(id) { cur ->
            val p = cur.pending
            if (p == null || p.seq != seq) cur
            else if (p.group.isNotEmpty()) { g = p.group; cur }
            else { val ng = app.libretag.nostr.MailboxPlan.newGroup(random); g = ng; cur.copy(pending = p.copy(group = ng)) }
        }
        return g
    }

    /**
     * Sign and publish entries one by one, 150 ms apart (P9): the late one and the untimed ones
     * first, then timed in ascending `at`. Every entry for report n is signed with sk_n (PROTOCOL.md
     * 4.2), the key that report commits to, and carries the command's ["g", group]. The relays that
     * accepted are counted into the command (R10).
     */
    private suspend fun publishEntries(t: Tracker, seq: Int, entries: List<MailboxEntry>) {
        if (entries.isEmpty()) return
        val group = groupOf(t.id, seq) ?: return
        val chain = chainFor(t) ?: return
        val ordered = app.libretag.nostr.MailboxPlan.order(entries, { it.atS }, { it.n })
        publishLock.withLock {
            var sent = 0
            var accepted = 0
            val plan = mutableListOf<Map<String, Any?>>()
            for (en in ordered) {
                if (!stillPending(t.id, seq)) return
                val nowS = System.currentTimeMillis() / 1000
                val exp = (en.atS ?: nowS) + en.ttlS
                if (en.atS != null && en.atS <= nowS + 5) continue // its slot has come
                // a newer report came meanwhile (the command was queued just before it arrived)
                if (!en.grid && app.libretag.nostr.MailboxPlan.stale(en.n, en.atS != null, store.get(t.id)?.lastN ?: -1, en.late)) continue
                val ce = chain.cmd(LibreTag.epoch(en.n)) ?: continue
                val tags = app.libretag.nostr.MailboxPlan.tags(en.tags, exp, group)
                val ev = NostrEvent.sign(LibreTag.mailboxSk(ce, en.n), nowS, LibreTag.NOSTR_COMMAND_KIND, tags,
                    Base64.getEncoder().encodeToString(en.pc))
                keepKey(t, seq, ev, exp, nowS, en.n)
                val ok = relays.publishCount(ev.toJson())
                accepted = maxOf(accepted, ok)
                fun tg(k: String) = en.tags.firstOrNull { it.size >= 2 && it[0] == k }?.get(1)
                plan += mapOf("n" to en.n, "k" to en.k, "at" to en.atS, "route" to tg("route"), "fb" to (tg("fb") ?: "1"), "late" to en.late)
                DiagLog.ev("cmd_sent", "tag" to t.id, "path" to "mailbox", "id" to ev.id, "seq" to seq, "n" to en.n, "k" to en.k, "xc" to en.pc.copyOf(4),
                    "at" to en.atS, "late" to en.late, "y" to en.tags.filter { it.size >= 2 && it[0] == "y" }.map { it[1] },
                    "route" to tg("route"), "fb" to tg("fb"), "relays_ok" to ok)
                sent++
                if (en.late) DiagLog.i(TAG, "${t.name}: late mailbox entry for n=${en.n}")
                delay(PUBLISH_GAP_MS)
            }
            if (sent > 0) DiagLog.ev("cmd_plan", "tag" to t.id, "seq" to seq, "entries" to plan)
            if (accepted > 0) store.updateTracker(t.id) { cur ->
                val q = cur.pending
                if (q == null || q.seq != seq) cur else cur.copy(pending = q.copy(accepted = maxOf(q.accepted, accepted)))
            }
            DiagLog.i(TAG, "${t.name}: $sent mailbox entries for seq=$seq, $accepted relay(s) took them")
        }
    }

    /** the command was not cancelled or replaced meanwhile (publishing runs in the background) */
    private fun stillPending(id: String, seq: Int) = store.get(id)?.pending?.seq == seq

    // ---------------------------------------------------------------- the ACK sender (SPEC.md 3.2, PROTOCOL.md 3.3, 4.3)

    /** when a notification about a tag went out (MORE for 30 s after it) */
    private val notifiedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun noteNotify(id: String) { notifiedAt[id] = System.currentTimeMillis() }

    private fun moreFor(t: Tracker): Boolean =
        app.libretag.data.QuietPlan.more(t.pending != null, detailOpenId == t.id, notifiedAt[t.id], System.currentTimeMillis())

    /**
     * The ACK for report [n] copy [j] (SPEC.md 3.2, the machine Opened -> LiveNode, LiveNostr, Timed ->
     * Sent -> Settled): from the own node zero-hop when it heard the copy directly, else an ACK entry
     * on the relays at once, else (late) timed entries for the next slots. Never a flood.
     */
    private suspend fun ackReport(t0: Tracker, n: Long, j: Int, pos: Position, receivedAtMs: Long, viaNode: Boolean, nodeDirect: Boolean,
                                  x: String?, choice: app.libretag.data.QuietPlan.AckChoice? = null) {
        val t = store.get(t0.id) ?: return
        if (t.lastN != n) return
        val standing = app.libretag.data.QuietPlan.still(t.positions.lastOrNull { it.n < n && it.via != VIA_USB_QUIET }, pos)
        val c = choice ?: app.libretag.data.QuietPlan.ackChoice(true, pos.approx, t.canCommand, j, viaNode, nodeDirect, nodeRxMeta,
            mesh.canZeroHop, reportAgeMs(if (viaNode) "mesh" else "nostr", receivedAtMs), standing)
        val hold = t.holdNow
        val more = moreFor(t)
        when (c.way) {
            app.libretag.data.QuietPlan.AckWay.NODE -> if (!sendNodeAck(t, n, 0, hold, more, timed = false, reportAt = receivedAtMs))
                timedAcks(t, n, c.form, hold, more, receivedAtMs, "node_failed")
            app.libretag.data.QuietPlan.AckWay.NOSTR -> if (x == null || !liveAckEntry(t, n, j, c.form, hold, more, x, receivedAtMs))
                timedAcks(t, n, c.form, hold, more, receivedAtMs, "relay_refused")
            app.libretag.data.QuietPlan.AckWay.TIMED -> timedAcks(t, n, c.form, hold, more, receivedAtMs, c.why)
            app.libretag.data.QuietPlan.AckWay.SKIP -> DiagLog.ev("ack", "tag" to t.id, "n" to n, "skip" to c.why)
        }
    }

    /** The near ACK Pa(n, k) zero-hop from the own node (`tx_now` when offered). True when the radio took it. */
    private suspend fun sendNodeAck(t: Tracker, n: Long, k: Int, hold: Int, more: Boolean, timed: Boolean, reportAt: Long = 0, atMs: Long? = null): Boolean {
        val ce = chainFor(t)?.cmd(LibreTag.epoch(n)) ?: return false
        val pa = app.libretag.proto.QuietMesh.ackSeal(ce, n, k, hold, more)
        val r = mesh.sendZeroHopNow(pa)
        DiagLog.ev("ack", "tag" to t.id, "n" to n, "xa" to pa.copyOf(4), "form" to "near", "timed" to timed, "k" to k, "at" to atMs,
            "path" to "node", "hold" to hold, "more" to more, "report_age_ms" to (if (reportAt > 0) System.currentTimeMillis() - reportAt else null),
            "request_ms" to r.requestMs, "sent_at" to r.sentAtMs, "ok" to r.ok, "error" to r.error)
        if (r.ok) store.updateTracker(t.id) { it.copy(lastAck = app.libretag.data.AckRec(n, r.sentAtMs ?: System.currentTimeMillis(), hold, "near", "node",
            timed = timed, reportAt = reportAt, xa = LibreTag.bytesToHex(pa.copyOf(4)))) }
        return r.ok
    }

    /**
     * A live ACK entry (PROTOCOL.md 4.3) for copy [j] whose x is [x]: near (route near) for a hello or a
     * moved flood, both forms (route direct) for a standing flood, routed (route direct, via the path
     * record, p the bridge of the last routed ACK) for copy 11. `p` from a receipt only within 500 ms.
     * If no bridge says it sent it within 10 s, timed entries follow.
     */
    private suspend fun liveAckEntry(t: Tracker, n: Long, j: Int, form: app.libretag.proto.QuietMesh.Form, hold: Int, more: Boolean, x: String, reportAt: Long): Boolean {
        val ce = chainFor(t)?.cmd(LibreTag.epoch(n)) ?: return false
        val nowS = System.currentTimeMillis() / 1000
        val routedForm = form == app.libretag.proto.QuietMesh.Form.ROUTED
        val p = if (routedForm) listOfNotNull(t.routedBridge) else bridges.receiptAuthors(x, RECEIPT_WAIT_MS).take(1)
        val tags = listOf(listOf("v", "2"), listOf("a", "1"), listOf("y", x), listOf("route", app.libretag.proto.QuietMesh.ackRoute(form))) +
            listOfNotNull(t.routedVia?.takeIf { routedForm }?.let { listOf("via", it) }) + p.map { listOf("p", it) } +
            listOf(listOf("expiration", (nowS + app.libretag.proto.QuietMesh.ACK_LIVE_TTL_S).toString()))
        val content = app.libretag.proto.QuietMesh.ackEntryContent(ce, n, 0, form, hold, more)
        val ev = NostrEvent.sign(LibreTag.mailboxSk(ce, n), nowS, LibreTag.NOSTR_COMMAND_KIND, tags, content)
        keepKey(t, ACK_SEQ, ev, nowS + app.libretag.proto.QuietMesh.ACK_LIVE_TTL_S, nowS, n)
        val ok = relays.publishCount(ev.toJson(), app.libretag.nostr.RelayPool.ONCE)
        val xa = LibreTag.bytesToHex(app.libretag.proto.QuietMesh.ackTag(ce, n, 0))
        DiagLog.ev("ack", "tag" to t.id, "n" to n, "xa" to xa, "form" to form.wire, "timed" to false, "k" to 0, "path" to "nostr",
            "p" to p.firstOrNull()?.take(16), "hold" to hold, "more" to more, "report_age_ms" to System.currentTimeMillis() - reportAt,
            "id" to ev.id, "relays_ok" to ok)
        if (ok == 0) return false
        store.updateTracker(t.id) { it.copy(lastAck = app.libretag.data.AckRec(n, System.currentTimeMillis(), hold, form.wire, "nostr", reportAt = reportAt, xa = xa)) }
        // Sent -> Timed: no "ack sent" note within 10 s means no bridge transmitted it
        scope.launch {
            delay(ACK_NOTE_WAIT_MS)
            val cur = store.get(t.id) ?: return@launch
            val a = cur.lastAck
            if (cur.lastN == n && a != null && a.n == n && a.path == "nostr" && a.noteAt == null)
                timedAcks(cur, n, form, hold, moreFor(cur), reportAt, "no_note")
        }
        return true
    }

    /**
     * Timed ACKs (PROTOCOL.md 4.3, R28): Pa(n, k) for the next slots on each grid, 2 for the near
     * form, 1 for the routed form, signed with sk of the anchor report, `at` t_k + 1, a `via` per
     * copy of the anchor, expiring at + 120 s; deleted once a newer report is in. Through the own
     * node as well when it heard the tag directly.
     */
    /** The timed ACK entries last published per tag, so a command queued after them can set MORE (PROTOCOL.md 3.3). */
    private data class TimedAckSet(val n: Long, val form: app.libretag.proto.QuietMesh.Form, val hold: Int, val more: Boolean,
                                   val reportAt: Long, val ids: Set<String>, val lastAtS: Long)
    private val timedAckSets = java.util.concurrent.ConcurrentHashMap<String, TimedAckSet>()

    /**
     * A command was queued after timed ACKs without MORE went out: a tag at a listen-only slot would close its
     * receiver right after the ACK and miss the command 300 ms behind it. The entries are deleted and published
     * again with MORE for the slots still ahead (the firmware also keeps the receiver on 1 s after such an ACK).
     */
    private suspend fun timedAcksWithMore(id: String) {
        val set = timedAckSets[id] ?: return
        val t = store.get(id) ?: return
        val nowS = System.currentTimeMillis() / 1000
        if (set.more || t.lastN != set.n || set.lastAtS <= nowS + 2) return
        timedAckSets.remove(id)
        val old = mailboxKeys.cancelIds(id, ACK_SEQ, set.ids)
        if (old.isNotEmpty()) publishDeletions(old)
        nodeSlotAcks.computeIfPresent(id) { _, l -> l.map { it.copy(more = true) } }
        DiagLog.ev("ack", "tag" to id, "n" to set.n, "timed" to true, "republish" to "more", "deleted" to old.size)
        timedAcks(t, set.n, set.form, set.hold, true, set.reportAt, "cmd_queued")
    }

    private suspend fun timedAcks(t0: Tracker, n: Long, form: app.libretag.proto.QuietMesh.Form, hold: Int, more: Boolean, reportAt: Long, why: String) {
        val t = store.get(t0.id) ?: return
        if (t.lastN != n) return
        val chain = chainFor(t) ?: return
        val ce = chain.cmd(LibreTag.epoch(n)) ?: return
        val now = System.currentTimeMillis()
        val slots = app.libretag.data.QuietPlan.timedAckSlots(Status.sendGrids(t, now), form, now)
        if (slots.isEmpty()) { DiagLog.ev("ack", "tag" to t.id, "n" to n, "timed" to true, "skip" to "no_grid", "why" to why); return }
        if (t.last?.nodeDirect == true && form != app.libretag.proto.QuietMesh.Form.ROUTED) nodeSlotAcks[t.id] = slots.map { (g, k) ->
            NodeSlotAck(n, g.n, k, g.t0 + (g.offsetsS[k - 1] + app.libretag.proto.QuietMesh.SLOT_SEND_AFTER_S) * 1000, hold, more)
        }.also { checksChanged.trySend(Unit) }
        val nowS = now / 1000
        val ids = mutableSetOf<String>()
        var lastAtS = 0L
        for ((g, k) in slots) {
            val ceA = chain.cmd(LibreTag.epoch(g.n)) ?: continue
            val atS = app.libretag.proto.QuietMesh.slotSendAt(g.t0 / 1000, g.offsetsS[k - 1])
            val xa = LibreTag.bytesToHex(app.libretag.proto.QuietMesh.ackTag(ce, n, k))
            val vias = g.vias.ifEmpty { listOfNotNull(chain.view(LibreTag.epoch(g.n))?.let { LibreTag.bytesToHex(LibreTag.reportTag(it, g.n, LibreTag.J_HOME)) }) }
            val exp = atS + app.libretag.proto.QuietMesh.ACK_TIMED_TTL_S
            val tags = listOf(listOf("v", "2"), listOf("a", "1"), listOf("y", xa), listOf("route", app.libretag.proto.QuietMesh.ackRoute(form)),
                listOf("at", atS.toString())) + vias.map { listOf("via", it) } + listOf(listOf("expiration", exp.toString()))
            val ev = NostrEvent.sign(LibreTag.mailboxSk(ceA, g.n), nowS, LibreTag.NOSTR_COMMAND_KIND, tags,
                app.libretag.proto.QuietMesh.ackEntryContent(ce, n, k, form, hold, more))
            keepKey(t, ACK_SEQ, ev, exp, nowS, g.n)
            ids += ev.id
            lastAtS = maxOf(lastAtS, atS)
            val ok = relays.publishCount(ev.toJson())
            DiagLog.ev("ack", "tag" to t.id, "n" to n, "xa" to xa, "form" to form.wire, "timed" to true, "k" to k, "at" to atS, "grid" to g.n,
                "path" to "nostr", "hold" to hold, "more" to more, "report_age_ms" to now - reportAt, "why" to why, "id" to ev.id, "relays_ok" to ok)
            delay(PUBLISH_GAP_MS)
        }
        if (ids.isNotEmpty()) timedAckSets[t.id] = TimedAckSet(n, form, hold, more, reportAt, ids, lastAtS)
        if (!more && store.get(t.id)?.pending != null) timedAcksWithMore(t.id) // a command came in while they went out
        store.updateTracker(t.id) { cur ->
            if (cur.lastAck?.n == n && !cur.lastAck.timed) cur
            else cur.copy(lastAck = app.libretag.data.AckRec(n, now, hold, form.wire, "nostr", timed = true, reportAt = reportAt))
        }
    }

    // ---------------------------------------------------------------- clock against the relays (R17)

    /** recent (phone arrival - created_at) of live report events, ms; the smallest is network delay plus the offset */
    private val relaySamples = ArrayDeque<Long>()
    /** the phone's clock minus the bridges' (ms, the smallest sample); applied above 2 s */
    @Volatile var relayOffsetMs: Long = 0L
        private set

    private fun noteRelayClock(createdAtS: Long) {
        val d = System.currentTimeMillis() - createdAtS * 1000
        if (kotlin.math.abs(d) > 10 * 60_000L) return // a backlog event, not a live one
        synchronized(relaySamples) {
            relaySamples.addLast(d)
            while (relaySamples.size > 20) relaySamples.removeFirst()
            relayOffsetMs = relaySamples.min()
        }
    }

    // ---------------------------------------------------------------- DMs (PROTOCOL.md 5)

    /** Each relay's answer to a DM; [ok] = how many accepted it. */
    data class DmResult(val acks: List<app.libretag.nostr.RelayAck>) {
        val ok: Int get() = acks.count { it.ok }
        val failed: List<app.libretag.nostr.RelayAck> get() = acks.filter { !it.ok }
    }

    /**
     * A DM from the tag's own Nostr key, sent from the phone over the internet: to our relays and the
     * recipient's inbox relays (NIP-65 read / NIP-17 10050), one short connection each, answering
     * NIP-42 AUTH with the tag's dm key. When no relay took it, the pool keeps retrying in the background.
     */
    suspend fun sendDm(t: Tracker, toPubkey: ByteArray, text: String, expiresAfterS: Long? = null): DmResult {
        val keys = keysFor(t) ?: return DmResult(emptyList())
        val nowS = System.currentTimeMillis() / 1000
        val inbox = inboxRelays(LibreTag.bytesToHex(toPubkey))
        if (inbox.dm.isNotEmpty()) {
            // 0.8.0: NIP-17 gift wrap, only to the recipient's kind 10050 relays (NIP-17 "Publishing");
            // AUTH with the wrap's one-time key, so the relay doesn't learn the tag's key either
            val w = app.libretag.nostr.Nip17.dm(keys.dmSk, toPubkey, text, nowS, expiresAfterS?.let { nowS + it })
            val r = DmResult(app.libretag.nostr.DmPublish.publish(inbox.dm, w.wrap, w.wrapSk))
            DiagLog.i(TAG, "${t.name}: NIP-17 DM to ${inbox.dm.size} inbox relay(s), ok=${r.ok}" +
                r.failed.joinToString("") { "; ${it.host}: ${it.reason}" })
            if (r.ok > 0) return r
        }
        // no kind 10050 (the recipient's client may not read NIP-17), or no inbox relay took it: NIP-04 as before
        val ev = app.libretag.nostr.TagDm.event(keys.dmSk, toPubkey, text, nowS, expiresAfterS)
        val targets = app.libretag.nostr.InboxRelays.dmTargets(store.relays, inbox.all)
        val r = DmResult(app.libretag.nostr.DmPublish.publish(targets, ev, keys.dmSk))
        DiagLog.i(TAG, "${t.name}: NIP-04 DM to ${targets.size} relay(s), ok=${r.ok}" +
            r.failed.joinToString("") { "; ${it.host}: ${it.reason}" })
        if (r.ok == 0) scope.launch { relays.publish(ev.toJson()) }
        return r
    }

    /** The tag's kind 0 (a recognisable sender in Nostr clients), to the same relays as its DM. */
    private suspend fun publishTagProfile(t: Tracker, toPubkey: ByteArray) {
        val keys = keysFor(t) ?: return
        val ev = app.libretag.nostr.TagProfile.event(keys.dmSk, System.currentTimeMillis() / 1000)
        val targets = app.libretag.nostr.InboxRelays.dmTargets(store.relays, inboxRelays(LibreTag.bytesToHex(toPubkey)).all)
        val ok = app.libretag.nostr.DmPublish.publish(targets, ev, keys.dmSk).count { it.ok }
        DiagLog.i(TAG, "${t.name}: profile to ${targets.size} relay(s), ok=$ok")
    }

    /**
     * The welcome DM to the owner (and the tag's profile). Null when no owner npub is set.
     * [Tracker.welcomeSentAt] is set only when at least one relay accepted the DM.
     */
    suspend fun sendWelcome(t: Tracker): DmResult? {
        val owner = store.ownerNpub?.let { Bech32.pubkey(it) } ?: return null
        if (!app.libretag.data.Welcome.canSend(t)) return DmResult(emptyList())
        val profile = scope.async { runCatching { publishTagProfile(t, owner) } }
        val r = sendDm(t, owner, getString(R.string.dm_welcome, t.name))
        profile.await()
        store.updateTracker(t.id) { app.libretag.data.Welcome.afterSend(it, r.ok, System.currentTimeMillis()) }
        return r
    }

    /** [dm]: the NIP-17 inbox (kind 10050 only); [all]: 10050 and 10002 read relays, for NIP-04 and the profile */
    data class Inbox(val dm: List<String>, val all: List<String>)

    private val inboxCache = HashMap<String, Pair<Long, Inbox>>()

    /** The recipient's inbox relays, from kinds 10002 and 10050 on our relays, cached 6 h. */
    private suspend fun inboxRelays(pubkeyHex: String): Inbox {
        synchronized(inboxCache) {
            inboxCache[pubkeyHex]?.takeIf { System.currentTimeMillis() - it.first < app.libretag.nostr.InboxRelays.CACHE_MS }?.let { return it.second }
        }
        val events = relays.query(listOf(buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(app.libretag.nostr.InboxRelays.KIND_RELAY_LIST)); add(JsonPrimitive(app.libretag.nostr.InboxRelays.KIND_DM_RELAYS)) })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
        }), 6_000).mapNotNull { NostrEvent.fromJson(it) }.filter { it.verify() }
        val found = events.ifEmpty {
            // the pool may be offline or its relays may not have the lists: ask once, plus an indexer
            app.libretag.nostr.RelayOnce.query(store.relays + INBOX_INDEXERS, listOf(buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(app.libretag.nostr.InboxRelays.KIND_RELAY_LIST)); add(JsonPrimitive(app.libretag.nostr.InboxRelays.KIND_DM_RELAYS)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkeyHex)) })
            }), 6_000).events.filter { it.verify() }
        }
        val list = Inbox(app.libretag.nostr.InboxRelays.dmRelays(found, pubkeyHex), app.libretag.nostr.InboxRelays.parse(found, pubkeyHex))
        // an empty answer (offline) is not cached for long
        synchronized(inboxCache) { inboxCache[pubkeyHex] = (if (found.isEmpty()) System.currentTimeMillis() - app.libretag.nostr.InboxRelays.CACHE_MS + 600_000L else System.currentTimeMillis()) to list }
        return list
    }

    suspend fun sendOwnerDm(t: Tracker, text: String, expiresAfterS: Long? = null): DmResult? {
        val owner = store.ownerNpub?.let { Bech32.pubkey(it) } ?: return null
        return sendDm(t, owner, text, expiresAfterS)
    }

    fun nostrNpub(pubkeyHex: String): String? = LibreTag.hexToBytes(pubkeyHex)?.let { Bech32.npub(it) }

    fun trackerNpub(t: Tracker): String? = keysFor(t)?.let { Bech32.npub(NostrEvent.xonly(it.dmSk)) }

    // ---------------------------------------------------------------- alarms

    fun setGuard(id: String, on: Boolean) {
        store.updateTracker(id) { t -> if (on && t.lastFix != null) t.guardHere(System.currentTimeMillis()) else t.guardOff() }
        if (!on) cancel(id, KIND_ALARM)
    }

    /** "Guard the new spot" after a guard alarm: the spot moves to the newest fix, the alarm ends. */
    fun rearmGuard(id: String) {
        store.updateTracker(id) { it.guardHere(System.currentTimeMillis()) }
        cancel(id, KIND_ALARM)
    }

    /** Boots after a new report: the BOOT bit, else a restart the counter gap shows (tag-diagnosis 4.3). */
    private fun bootRecsAfter(cur: Tracker, pos: Position): List<app.libretag.data.BootRec> {
        if (cur.bootRecs.any { it.n == pos.n }) return cur.bootRecs
        val prev = app.libretag.data.CounterGaps.reports(cur).lastOrNull { it.n < pos.n }
        val rec = when {
            pos.boot -> app.libretag.data.BootRec(pos.receivedAt, pos.n, pos.bootCause, pos.bootResets)
            prev != null && app.libretag.data.CounterGaps.classify(cur, prev, pos).kind == app.libretag.data.CounterGaps.Kind.RESTART ->
                app.libretag.data.BootRec(pos.receivedAt, pos.n, inferred = true)
            else -> return cur.bootRecs
        }
        return (cur.bootRecs + rec).sortedBy { it.at }.takeLast(6)
    }

    /**
     * The phone's clock against a trusted time (4.6): a fix time or a relay event in the phone's
     * future by more than 2 min means the phone is behind. A sane sample clears it.
     */
    private fun noteClock(skewMs: Long) {
        val v = when {
            skewMs < -app.libretag.data.Diagnosis.CLOCK_SKEW_MS -> skewMs
            kotlin.math.abs(skewMs) < app.libretag.data.Diagnosis.CLOCK_SKEW_MS -> 0L
            else -> return
        }
        if (clockSkewMs.value != v) { clockSkewMs.value = v; diagContextVersion.value++ }
    }

    /** "Tag is silent": past the expected report (next_in, or the interval; the heartbeat after QUIET) + 30 min. */
    private suspend fun watchSilence() {
        while (true) {
            delay(5 * 60_000L)
            checkSilence()
        }
    }

    /** the last status line logged per tag (field log `status`, on every change) */
    private val statusLogged = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun checkSilence() {
        val now = System.currentTimeMillis()
        store.lastSilenceCheck = now
        for (t in store.trackers.value) {
            val s = Status.of(t, now)
            val link = app.libretag.data.QuietPlan.link(t)
            val deadline = Status.silentAt(t, now)
            val key = "${s.kind}|$link|${deadline?.div(60_000)}|${t.anchor.known}"
            if (statusLogged.put(t.id, key) != key) DiagLog.ev("status", "tag" to t.id, "state" to s.kind.name,
                "link" to link.name.lowercase(), "deadline" to deadline, "schedule_known" to t.anchor.known, "missed" to s.missed)
            // overdue (also in Lost, which outranks Silent in the status) or never heard: maybe past the window
            val overdue = Status.nextAt(t, now)?.let { now > it + Status.SILENT_MARGIN_MS } ?: (t.lastN < 0 && now - t.createdAt > 3600_000L)
            if (overdue) maybeResync(t, now)
            val c = diagContext(t, now)
            val d = app.libretag.data.Diagnosis.of(t, s, c, now)
            // 0.12: receipts for the counters the app has not seen (4.4)
            if (overdue) maybeAskAhead(t, now)
            // 0.14.0 (R13): before "silent", is the bridge that heard it offline?
            if (overdue || Status.of(t, now).missed > 0) maybeCheckBridges(t, now)
            // "<Tag> is heard but does not get the answers": once per report counter
            if (app.libretag.data.QuietPlan.noAnswers(t) && t.noAnswersNotified != t.lastN && t.alerts.silent) {
                store.updateTracker(t.id) { it.copy(noAnswersNotified = t.lastN) }
                notify(t.id, KIND_NO_ANSWERS, getString(R.string.notif_no_answers_title, t.name), getString(R.string.dg_find_no_answers), alarm = false)
                DiagLog.ev("notify", "tag" to t.id, "kind" to "no_answers", "value" to t.lastN)
            }
            // "Car keeps restarting": once per episode, also while it reports
            app.libretag.data.SilentNotify.restartNotice(t, now)?.let { start ->
                store.updateTracker(t.id) { it.copy(restartNotified = start) }
                if (t.alerts.silent) notify(t.id, KIND_RESTART, getString(R.string.notif_restart_title, t.name), getString(R.string.notif_restart_text))
            }
            if (app.libretag.data.SilentNotify.updateNotice(t, d, now)) {
                store.updateTracker(t.id) { it.copy(dfuNotified = true) }
                if (t.alerts.silent) notify(t.id, KIND_SILENT, getString(R.string.notif_update_title, t.name), getString(R.string.notif_update_text))
            }
            val last = t.last ?: continue
            when (app.libretag.data.SilentNotify.decide(t.silentAlerted, t.alerts.silent, s, d)) {
                app.libretag.data.SilentNotify.Out.POST -> {
                    store.updateTracker(t.id) { it.copy(silentAlerted = true) }
                    val title = getString(R.string.notif_silent_title, t.name, Format.duration(this, s.silentForMs))
                    val text = if (d == null || d.cause == app.libretag.data.Diagnosis.Cause.UNKNOWN) {
                        getString(R.string.notif_silent_text, Format.every(this, s.intervalS ?: (t.reportPeriodMin * 60)), posSummary(t.lastFix ?: last))
                    } else getString(R.string.notif_silent_cause, app.libretag.ui.DiagText.row(this, t, s, d, now), posSummary(t.lastFix ?: last))
                    notify(t.id, KIND_SILENT, title, text)
                }
                app.libretag.data.SilentNotify.Out.CANCEL -> {
                    store.updateTracker(t.id) { it.copy(silentAlerted = false) }
                    cancel(t.id, KIND_SILENT)
                }
                app.libretag.data.SilentNotify.Out.HOLD, app.libretag.data.SilentNotify.Out.NONE -> {}
            }
        }
        // a guarded tag and the phone can't receive: say so once per blind stretch
        val list = store.trackers.value
        list.firstOrNull()?.let { first ->
            val c = diagContext(first, now)
            if (app.libretag.data.SilentNotify.blindNotice(list, c, now, prefs.blindNotifiedFor)) {
                prefs.blindNotifiedFor = c.blindFrom ?: 0
                notify("app", KIND_BLIND, getString(R.string.notif_blind_title), getString(R.string.notif_blind_text),
                    channel = CH_ALERT, alarm = false)
            }
        }
    }

    private val bridgeCheckAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * R13: the bridges that heard the tag's last reports, with their newest announcements: when all
     * of them are offline (announcement older than 2 h, or radio down), the diagnosis says "the
     * bridge that hears it is offline" instead of an alarm. Asked at most every 15 minutes.
     */
    private fun maybeCheckBridges(t: Tracker, now: Long) {
        if (now - (bridgeCheckAt[t.id] ?: 0L) < BRIDGE_CHECK_MS || relays.connected.value.first == 0) return
        if (t.last?.via != "nostr" && t.last?.alsoVia != "nostr") return
        bridgeCheckAt[t.id] = now
        scope.launch {
            val chain = chainFor(t) ?: return@launch
            val ys = app.libretag.nostr.BridgeTargets.recentYs(t.lastN, null, { n, j ->
                chain.view(LibreTag.epoch(n))?.let { LibreTag.bytesToHex(LibreTag.reportTag(it, n, j)) } }, count = 3).keys
            val anns = runCatching { bridges.hearers(ys) }.getOrDefault(emptyList())
            val nowS = System.currentTimeMillis() / 1000
            val down = anns.mapNotNull { a -> a.offlineSince(nowS)?.let { a to it } }
            val seen = if (anns.isNotEmpty() && down.size == anns.size) down.maxBy { it.second }.let { (a, since) ->
                app.libretag.data.BridgeDownSeen(since * 1000, a.name, a.radioUp == false, now) } else null
            DiagLog.ev("bridge_check", "tag" to t.id, "bridges" to anns.size, "offline" to down.size, "since" to seen?.sinceMs)
            val cur = bridgeDown.value
            if (seen != null) bridgeDown.value = cur + (t.id to seen) else if (t.id in cur) bridgeDown.value = cur - t.id
            diagContextVersion.value++
            if (seen != null) DiagLog.ev("notify", "tag" to t.id, "kind" to "bridge_offline", "value" to seen.sinceMs, "shown" to "diagnosis")
        }
    }

    /**
     * "Car left Home at 22:01" / "Car arrived at Home at 18:40" on the Places channel: information
     * at default importance, never an alarm. [place] may be [WifiZoneTransit.UNNAMED].
     */
    private fun notifyPlace(t: Tracker, place: String, leave: Boolean, atMs: Long, pos: Position) {
        val at = Format.clock(this, atMs)
        val title = if (place == app.libretag.data.WifiZoneTransit.UNNAMED)
            getString(if (leave) R.string.notif_left_wifi else R.string.notif_arrived_wifi, t.name, at)
        else getString(if (leave) R.string.notif_left else R.string.notif_arrived, t.name, place, at)
        // leaving and arriving replace each other: one place notification per tag
        notify(t.id, KIND_PLACE, title, getString(R.string.notif_place_text, posSummary(pos), nextSummary(t)), channel = CH_PLACES, alarm = false)
    }

    private fun notify(id: String, kind: Int, title: String, text: String, channel: String = CH_ALERT, onlyOnce: Boolean = false, alarm: Boolean = true) {
        DiagLog.ev("notify", "tag" to id, "kind" to kind, "channel" to channel, "alarm" to alarm)
        noteNotify(id) // MORE for 30 s: the owner may answer with a command

        val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAG, id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, (id + kind).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (alarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (alarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(onlyOnce)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify((id + kind).hashCode(), n)
        } catch (e: SecurityException) {
            DiagLog.w(TAG, "notification permission missing")
        }
    }

    /** The tag answered the wake advert while no screen that connects is open. */
    fun notifyBleListening(t: Tracker) {
        notify(t.id, KIND_BLE_WAKE, getString(R.string.blecon_wake_notif_title, t.name), getString(R.string.blecon_wake_notif_text),
            onlyOnce = true, alarm = false)
    }

    fun cancelBleListening(id: String) = runCatching { cancel(id, KIND_BLE_WAKE) }

    private fun cancel(id: String, kind: Int) = NotificationManagerCompat.from(this).cancel((id + kind).hashCode())

    /** Notification preview from Settings (sample text, no real tag). */
    fun previewNotification() {
        notify("preview", KIND_SILENT, getString(R.string.notif_preview_title), getString(R.string.notif_preview_text))
    }

    // ---------------------------------------------------------------- tags

    private var removed: Pair<Tracker, Int>? = null

    fun removeTag(id: String) {
        val list = store.trackers.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        removed = list[i] to i
        store.remove(id)
        forgetWindow(id)
        anchorBook.forget(id)
    }

    fun undoRemove(): Boolean {
        val (t, i) = removed ?: return false
        store.restore(t, i)
        removed = null
        return true
    }

    companion object {
        const val TAG = "LibreTag"
        /** relay-list indexers asked when our relays have no inbox list for a DM recipient */
        val INBOX_INDEXERS = listOf("wss://purplepag.es")
        const val CH_SERVICE = "service"
        const val CH_ALERT = "alerts"
        const val CH_LOST = "lost"
        /** 0.9: a tag left or arrived at a place (per-place choice, Tracker.notifies) */
        const val CH_PLACES = "places"
        const val KIND_BATT = 1
        const val KIND_ALARM = 2
        const val KIND_SILENT = 3
        const val KIND_LOST = 4
        /** 0.9: left / arrived at a place (its own notification id, so a guard alarm is never replaced) */
        const val KIND_PLACE = 5
        /** 0.12: "keeps restarting" (once per episode) and "can't receive reports" (blind with a guarded tag) */
        const val KIND_RESTART = 6
        const val KIND_BLIND = 7
        /** 0.13.1: the tag answered the wake advert, tap to connect */
        const val KIND_BLE_WAKE = 8
        /** 0.14.0: Lost or Track ended; a command did not reach the tag; heard but no answers */
        const val KIND_MODE_END = 9
        const val KIND_NOT_DELIVERED = 10
        const val KIND_NO_ANSWERS = 11
        /** the tracker raises LOW_BATT itself; this is the app's own threshold for older reports */
        const val LOW_BATT_PCT = 20
        /** the first firmware that seals reports v2 (PROTOCOL.md 2) */
        const val REPORT_V2_FIRMWARE = "0.9.0"
        /** the oldest tracker firmware the app works with; older shows "update the firmware" */
        const val MIN_FIRMWARE = "0.9.5"
        const val LOW_BATT_RESET_PCT = 40
        /** the tracker listens 60 s after a report; leave margin for the flood back */
        const val CMD_FRESH_MS = 40_000L
        /** timed mailbox entries for at most this many slots per grid (a bridge keeps 12 per via) */
        const val MAILBOX_CHECKS = 12
        const val CHECK_IDLE_POLL_MS = 60_000L
        /** own-node slot sends: retried every 3 s until t_k + 20 s (R9) */
        const val SLOT_RETRY_MS = 3_000L
        const val SLOT_RETRY_UNTIL_MS = 20_000L
        /** R11: frames in the first 20 s after the node connected are approximate */
        const val NODE_QUEUED_MS = 20_000L
        /** how far back a queued frame may lie when the app never saw the node go down */
        const val NODE_QUEUED_GUESS_MS = 3600_000L
        /** MailboxKeys seq of ACK entries (commands use 1..255) */
        const val ACK_SEQ = 0
        /** the ACK first, the command 300 ms later (PROTOCOL.md 4.3) */
        const val ACK_BEFORE_CMD_MS = 300L
        /** a receipt names the bridge of a live ACK only within 500 ms (4.3) */
        const val RECEIPT_WAIT_MS = 500L
        /** no "ack sent" note within 10 s: timed ACKs follow */
        const val ACK_NOTE_WAIT_MS = 10_000L
        /** between events of one publish run (P9) */
        const val PUBLISH_GAP_MS = 150L
        /** the clock offset against the relays is applied above this (R17) */
        const val CLOCK_APPLY_MS = 2_000L
        const val COMMAND_LOOP_MS = 30_000L
        const val DOZE_GAP_MS = 90_000L
        /** blind Lost: at most 3 tries (PROTOCOL.md 4.3) */
        const val BLIND_TRIES = 3
        const val BRIDGE_CHECK_MS = 15 * 60_000L
        const val CHECK_SLOT_TTL_S = 600L
        /** bridge claims older than this are not news (bridges ignore them after 30 s) */
        const val CLAIM_MAX_AGE_S = 120L
        const val GUARD_M = 300.0
        /** leaving a zone counts only this far outside its edge (a bad first fix after waking) */
        const val ZONE_HYST_M = 50
        const val MAX_ESTIMATES_PER_RUN = 60
        /** reports of a newly added tag are looked for this far back (relays keep them 14 days) */
        const val FIRST_SYNC_S = 14 * 86400L
        /** the fetch-all live subscription is renewed, and the backlog fetched, this often */
        const val SYNC_EVERY_MS = 30 * 60_000L
        /** tags per window REQ */
        const val WINDOW_CHUNK = 256
        const val UNMATCHED_MAX = 20_000
        const val RESYNC_EVERY_MS = 6 * 3600_000L
        /** a fix the tag kept while quiet (history j = 3), never sent */
        const val VIA_USB_QUIET = "usb-quiet"
        /** receipts ahead: how often a late tag's relays are asked (detail open / otherwise) */
        const val AHEAD_DETAIL_MS = 15 * 60_000L
        const val AHEAD_EVERY_MS = 3600_000L
        lateinit var instance: LibreTagApp
            private set
    }
}

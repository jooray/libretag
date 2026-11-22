package app.libretag.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.ble.BleSeen
import app.libretag.ble.BleSerial
import app.libretag.data.Tracker
import app.libretag.data.Places
import app.libretag.data.WifiZoneRec
import app.libretag.proto.LibreTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the phone found on the cable. */
enum class UsbFind {
    /** not paired with any phone: prints its key */
    NEW,
    /** locked to this phone, unlocked with our key for this connection */
    MINE,
    /** locked, ours by id, but the unlock answer was refused */
    UNLOCK_FAILED,
    /** locked to someone else's phone */
    FOREIGN,
    /** answers, but not as a LibreTag tag (other firmware) */
    NOT_A_TAG,
}

/** How the console is connected. */
enum class Via { USB, BLE }

data class UsbTag(
    val find: UsbFind,
    val status: TrackerStatus,
    /** K0: printed by an unlocked tag, or ours from the store */
    val k0Hex: String?,
    /** the tag in our store, if any */
    val tagId: String?,
    val version: String?,
    val board: String?,
    /** commands the firmware lists in `help` (zone, anchor, blepair, ...) */
    val commands: Set<String>,
    val productName: String?,
    /** Wi-Fi zones on the tag (`wzone list`); null when the firmware has no Wi-Fi scanner */
    val wifiZones: List<WifiZoneEntry>? = null,
    /** how the console is connected (0.8.0: the USB cable or Bluetooth) */
    val via: Via = Via.USB,
    /** 0.12: the `quiet` command's answer ("none", "zone", "owner", "parked"), null when not asked */
    val quiet: String? = null,
) {
    /** the tag scans Wi-Fi: `help` lists `wzone`, or `status` prints the Wi-Fi zone line */
    val wifi: Boolean get() = wifiZones != null || "wzone" in commands || status.wifiZones != null
}

sealed interface UsbState {
    data object None : UsbState
    data object NeedPermission : UsbState
    data object Connecting : UsbState
    data class Connected(val tag: UsbTag) : UsbState
    data class Failed(val message: String) : UsbState
}

/**
 * The tag on the USB cable, shared by the Add tag wizard, the Service sheet, the zone editor and
 * Known devices. One console at a time; every call runs on IO under a lock.
 */
class UsbTagSession(private val app: LibreTagApp) {
    private val _state = MutableStateFlow<UsbState>(UsbState.None)
    val state: StateFlow<UsbState> = _state
    private var console: TrackerConsole? = null
    private val lock = Mutex()

    val connected: UsbTag? get() = (state.value as? UsbState.Connected)?.tag

    fun needPermission() { _state.value = UsbState.NeedPermission }

    suspend fun connect(usb: UsbManager, device: UsbDevice): UsbState = lock.withLock {
        _state.value = UsbState.Connecting
        val r = withContext(Dispatchers.IO) {
            runCatching {
                closeConsole()
                val c = TrackerConsole(UsbSerial.open(usb, device))
                console = c
                // right after plug-in the tag may still be booting
                var s = c.status()
                var tries = 0
                while (s.idHex == null && tries++ < 5) {
                    delay(800)
                    s = c.status()
                }
                if (s.idHex == null) {
                    // 0.12: a Heltec that does not answer may sit in the ESP32 ROM's download mode
                    val port = c.port()
                    if (port is UsbSerial && port.isCp210x) app.cable.setEspDownload(espSyncProbe(port))
                    return@runCatching UsbTag(UsbFind.NOT_A_TAG, s, null, null, null, null, emptySet(), device.productName)
                }
                app.cable.setEspDownload(false)
                probe(c, s, null, device.productName)
            }
        }
        finish(r)
    }

    /**
     * The tag over Bluetooth (PROTOCOL.md 6.2): it advertised for one of our keys and the handshake
     * proved both sides, so there is no `challenge`. MINE when `status` prints the id we expect.
     * The cable wins: with a tag on the cable this does nothing.
     */
    suspend fun connectBle(ctx: Context, seen: BleSeen): UsbState = lock.withLock {
        if (connected?.via == Via.USB) return@withLock state.value
        val t = app.store.get(seen.tagId) ?: return@withLock state.value
        val keys = app.consoleFor(t) ?: return@withLock state.value
        _state.value = UsbState.Connecting
        val r = withContext(Dispatchers.IO) {
            runCatching {
                closeConsole()
                val link = try {
                    BleSerial.open(ctx, seen.device, keys.usbKey)
                } catch (e: java.io.IOException) {
                    if (e.message?.contains("refused the key") == true || e.message?.contains("verify") == true) throw e
                    // one more try: a first connect often fails (GATT 133) on Android
                    DiagLog.w(TAG, "ble: ${e.message}, trying again")
                    delay(800)
                    BleSerial.open(ctx, seen.device, keys.usbKey)
                }
                link.onLost = { app.scope.launch { bleLost(t.id, link) } }
                val c = TrackerConsole(link)
                console = c
                val s = c.status()
                if (s.idHex == null || s.idHex != LibreTag.bytesToHex(keys.id)) {
                    closeConsole()
                    throw java.io.IOException("not this tag")
                }
                probe(c, s, t, null)
            }
        }
        if (r.isSuccess) _bleNote.value = null
        else DiagLog.w(TAG, "ble connect: ${r.exceptionOrNull()?.message}")
        finish(r)
    }

    /** After the first `status`: who the tag is, the unlock (USB), version, help, owner, Wi-Fi zones. */
    private suspend fun probe(c: TrackerConsole, first: TrackerStatus, bleTag: Tracker?, productName: String?): UsbTag {
        var s = first
        val (version, board) = parseVersion(c.command("version"))
        var key = s.keyHex
        var find = UsbFind.NEW
        var tagId: String? = null
        if (bleTag != null) {
            // over Bluetooth the handshake was the unlock
            find = UsbFind.MINE; key = bleTag.keyHex.ifEmpty { null }; tagId = bleTag.id
        } else if (s.locked) {
            val mine = app.store.trackers.value.firstOrNull { t ->
                app.consoleFor(t)?.idHex == s.idHex
            }
            val keys = mine?.let { app.consoleFor(it) }
            when {
                keys == null -> find = UsbFind.FOREIGN
                c.unlock(keys.usbKey) -> { find = UsbFind.MINE; key = mine.keyHex.ifEmpty { null }; tagId = mine.id; s = c.status() }
                else -> { find = UsbFind.UNLOCK_FAILED; tagId = mine.id }
            }
        } else if (key != null) {
            tagId = app.store.trackers.value.firstOrNull { it.keyHex.equals(key, ignoreCase = true) }?.id
            if (tagId != null) find = UsbFind.MINE
        }
        val commands = if (find == UsbFind.MINE || find == UsbFind.NEW) helpCommands(c.command("help", maxMs = 2000)) else emptySet()
        // 0.8.8: our tag without an owner npub (the wizard's owner step failed in 0.8.7, or the
        // owner was set in the app later): send it now, the tag DMs its owner from then on
        if (find == UsbFind.MINE) ownerToSend(s, app.store.ownerNpub, commands)?.let { npub ->
            val ok = c.command("owner $npub").any { it.startsWith("owner npub1") }
            DiagLog.i(TAG, "owner npub sent to the tag: ${if (ok) "ok" else "no answer"}")
            if (ok) s = c.status()
        }
        // a Wi-Fi tag (ESP32): its zones are taken on the spot, read them now
        val wifiZones = if ((find == UsbFind.MINE || find == UsbFind.NEW) && ("wzone" in commands || s.wifiZones != null))
            WifiZoneParsers.parseList(c.command(WifiZoneCommands.LIST)) else null
        // 0.12: why it is quiet, for the diagnosis ("with you" when it sees one of your devices)
        val quiet = if (find == UsbFind.MINE && "quiet" in commands) TrackerStatus.parseQuiet(c.command("quiet")) else null
        return UsbTag(find, s, key, tagId, version, board, commands, productName, wifiZones,
            if (bleTag != null) Via.BLE else Via.USB, quiet)
    }

    /**
     * The ROM loader's SYNC frame (46 bytes of garbage for a busy firmware's console, harmless) and
     * whether the ROM answered within 1.5 s. Needs a bench check on the Heltec V3.
     */
    private fun espSyncProbe(port: UsbSerial): Boolean = runCatching {
        port.write(EspSync.FRAME)
        val got = java.io.ByteArrayOutputStream()
        val until = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < until) {
            got.write(port.read(200))
            if (EspSync.isRomAnswer(got.toByteArray())) return@runCatching true
        }
        false
    }.getOrDefault(false).also { DiagLog.i(TAG, "esp32 sync probe: ${if (it) "ROM download mode" else "no answer"}") }

    /** "Restart the tag" for a Heltec in download mode: pulse EN through the CP2102, then the screen reconnects. */
    suspend fun espRestart(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val port = console?.port() as? UsbSerial ?: return@withContext false
            runCatching { port.cpReset() }.isSuccess.also {
                closeConsole()
                _state.value = UsbState.None
                app.cable.setEspDownload(false)
            }
        }
    }

    private fun finish(r: Result<UsbTag>): UsbState {
        val st = r.fold({ UsbState.Connected(it) }, { UsbState.Failed(it.message ?: it.javaClass.simpleName) })
        _state.value = st
        (st as? UsbState.Connected)?.tag?.let { tag ->
            if (tag.tagId != null) app.store.updateTracker(tag.tagId) {
                val now = System.currentTimeMillis()
                it.copy(firmware = tag.version ?: it.firmware, board = tag.board ?: it.board,
                    lastUsbAt = if (tag.via == Via.USB) now else it.lastUsbAt,
                    health = if (tag.find == UsbFind.MINE) TrackerStatus.health(tag.status, tag.quiet, now) else it.health)
            }
            if (tag.tagId != null && tag.find == UsbFind.MINE && tag.wifi) storeWifiZones(tag.tagId, tag.wifiZones)
            if (tag.find == UsbFind.MINE) adoptSettings(tag)
        }
        return st
    }

    private fun closeConsole() {
        console?.let { runCatching { it.close() } }
        console = null
    }

    // ---------------------------------------------------------------- Bluetooth (0.8.0)

    /** What the app says after a Bluetooth session ended without the user ending it. */
    enum class BleNote { RECONNECTING, RECONNECTED, LOST, TAG_CLOSED }
    private val _bleNote = MutableStateFlow<BleNote?>(null)
    val bleNote: StateFlow<BleNote?> = _bleNote
    fun bleNoteShown() { if (_bleNote.value != BleNote.RECONNECTING) _bleNote.value = null }

    /**
     * The Bluetooth link went away. A running operation already failed as with a pulled cable. If
     * the tag ended the session itself (after 30 minutes) that is said; a lost link gets one
     * automatic reconnect inside the tag's 30 s grace (the interrupted operation is not replayed).
     */
    private suspend fun bleLost(tagId: String, link: BleSerial) {
        val reason = link.closingReason
        lock.withLock {
            if (console?.port() === link) console = null
            if (connected?.via == Via.BLE) _state.value = UsbState.None
        }
        app.ble.forget(tagId)
        when (reason) {
            "idle", "keys" -> { _bleNote.value = null; return }
            "max" -> { _bleNote.value = BleNote.TAG_CLOSED; return }
        }
        if (link.userClosed) return
        _bleNote.value = BleNote.RECONNECTING
        app.ble.acquire(app)
        try {
            val seen = withTimeoutOrNull(35_000) {
                app.ble.seen.first { m -> m[tagId]?.let { System.currentTimeMillis() - it.at < 5_000 } == true }[tagId]
            }
            val ok = seen != null && state.value !is UsbState.Connected && connectBle(app, seen) is UsbState.Connected
            _bleNote.value = if (ok) BleNote.RECONNECTED else BleNote.LOST
        } finally {
            app.ble.release()
        }
    }

    /** Ends a Bluetooth session (the user's choice, or the cable came). */
    fun closeBle() {
        if (connected?.via != Via.BLE) return
        (console?.port() as? BleSerial)?.userClosed = true
        closeConsole()
        _state.value = UsbState.None
    }

    /** The keepalive while a screen of the tag is open: an empty line runs no command. */
    suspend fun keepAlive() {
        if (connected?.via != Via.BLE || lock.isLocked) return
        lock.withLock { withContext(Dispatchers.IO) { runCatching { console?.port()?.write("\r".toByteArray()) } } }
    }

    /** A fast Bluetooth link for a long listing (history), balanced again afterwards. */
    fun fastLink(on: Boolean) { (console?.port() as? BleSerial)?.fast(on) }

    val via: Via? get() = connected?.via

    /** Tag id whose settings were just read from the tag and differed from the app's (the UI shows a note once). */
    private val _settingsRead = MutableStateFlow<String?>(null)
    val settingsRead: StateFlow<String?> = _settingsRead
    fun settingsReadShown() { _settingsRead.value = null }

    /**
     * The settings the tag printed in `status` become the app's (and so the backup's). Not while the
     * app still has changes of its own to write over the cable ([Tracker.cableDirty]): those win.
     */
    private fun adoptSettings(tag: UsbTag) {
        val id = tag.tagId ?: return
        val cur = app.store.get(id) ?: return
        if (cur.cableDirty || !cur.hasConsole) return
        val next = TagSettingsRead.adopt(cur, tag.status, tag.version)
        if (!TagSettingsRead.settingsDiffer(cur, next)) {
            // a full-access share knows the interval from its first read on (Tracker.intervalKnown)
            if (cur.sharedAdmin && cur.settingsReadAt == 0L) app.store.updateTracker(id) { it.copy(settingsReadAt = System.currentTimeMillis()) }
            return
        }
        DiagLog.i(TAG, "settings read from the tag: interval=${next.intervalMin} every=${next.reportEvery} check=${next.checkS} still=${next.stillAfter}/${next.stillHbH}h hb=${next.zoneHbH}/${next.ownerHbH}h")
        app.store.updateTracker(id) { TagSettingsRead.adopt(it, tag.status, tag.version).copy(settingsReadAt = System.currentTimeMillis()) }
        app.backup.schedule()
        _settingsRead.value = id
    }

    /** The tag scans Wi-Fi; [zones] as just read from it (null: not read, keep what the app has). */
    fun storeWifiZones(id: String, zones: List<WifiZoneEntry>?) {
        app.store.updateTracker(id) { cur ->
            if (zones == null) cur.copy(wifiTag = true)
            else cur.copy(wifiTag = true,
                wifiZones = Places.carryNotify(cur.wifiZones, zones.map { WifiZoneRec(it.name, it.aps, it.scans) }),
                wifiZonesAt = System.currentTimeMillis())
        }
        // the cable also says which Wi-Fi zones exist: keep the app's copy in step
        update { if (it.tagId == id && zones != null) it.copy(wifiZones = zones) else it }
    }

    /** Try the unlock again (the wizard's "Try again"). */
    suspend fun retryUnlock(): Boolean = lock.withLock {
        val tag = connected ?: return@withLock false
        val c = console ?: return@withLock false
        val t = tag.tagId?.let { app.store.get(it) } ?: return@withLock false
        val keys = app.consoleFor(t) ?: return@withLock false
        val ok = withContext(Dispatchers.IO) { runCatching { c.unlock(keys.usbKey) }.getOrDefault(false) }
        if (ok) {
            val s = withContext(Dispatchers.IO) { c.status() }
            val commands = withContext(Dispatchers.IO) { helpCommands(c.command("help", maxMs = 2000)) }
            val next = tag.copy(find = UsbFind.MINE, status = s, k0Hex = t.keyHex.ifEmpty { null }, commands = commands)
            _state.value = UsbState.Connected(next)
            adoptSettings(next)
        }
        ok
    }

    fun disconnected() {
        closeConsole()
        _state.value = UsbState.None
    }

    /** The cable left: a Bluetooth session has nothing to do with it and stays. */
    fun usbDetached() {
        if (connected?.via == Via.BLE || console?.port() is BleSerial) return
        disconnected()
    }

    /** One console command (blocking IO under the lock). Null when no tag is connected. */
    suspend fun command(cmd: String, quietMs: Int = 400, maxMs: Int = 3000): List<String>? = lock.withLock {
        val c = console ?: return@withLock null
        withContext(Dispatchers.IO) { runCatching { c.command(cmd, quietMs, maxMs) }.getOrNull() }
    }

    /** Run several commands under one lock. */
    suspend fun <T> session(block: (TrackerConsole) -> T): T? = lock.withLock {
        val c = console ?: return@withLock null
        withContext(Dispatchers.IO) { runCatching { block(c) }.onFailure { DiagLog.w(TAG, "usb: ${it.message}") }.getOrNull() }
    }

    suspend fun refreshStatus(): TrackerStatus? {
        val s = session { it.status() } ?: return null
        connected?.let { _state.value = UsbState.Connected(it.copy(status = s, k0Hex = s.keyHex ?: it.k0Hex)) }
        return s
    }

    /** Re-read after a reset (`unpair`) or pairing: the tag now prints its new key. */
    fun update(change: (UsbTag) -> UsbTag) {
        connected?.let { _state.value = UsbState.Connected(change(it)) }
    }

    /** Whether the connected firmware knows a command (from `help`). */
    fun supports(cmd: String): Boolean = connected?.commands?.contains(cmd) == true

    fun tagFor(t: Tracker): Boolean = connected?.tagId == t.id && connected?.find == UsbFind.MINE

    companion object {
        const val TAG = "UsbTagSession"
        private val VERSION = Regex("""LibreTag tracker (\S+) on ZephCore (\S+?),? board (\S+)""")

        fun parseVersion(lines: List<String>): Pair<String?, String?> {
            val m = lines.firstNotNullOfOrNull { VERSION.find(it) } ?: return null to null
            return m.groupValues[1] to m.groupValues[3]
        }

        /**
         * The owner npub to send over an unlocked console ([s] read after the unlock): the app has one,
         * the firmware knows `owner`, and the tag's full `status` (its radio line is only in the
         * unlocked one) shows none. Null: nothing to send.
         */
        fun ownerToSend(s: TrackerStatus, appNpub: String?, commands: Set<String>): String? =
            appNpub?.takeIf { "owner" in commands && s.owner == null && s.idHex != null && s.radio != null }

        /** Command words from the firmware's `help` lines ("status | version | ..."). */
        fun helpCommands(lines: List<String>): Set<String> = lines.flatMap { l ->
            l.split('|').mapNotNull { it.trim().split(' ').firstOrNull()?.takeIf { w -> w.matches(Regex("[a-z]+")) } }
        }.toSet()
    }
}

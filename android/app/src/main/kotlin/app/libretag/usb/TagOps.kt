package app.libretag.usb

import app.libretag.DiagLog
import app.libretag.LibreTagApp
import app.libretag.data.KnownDevice
import app.libretag.data.Tracker

/** What a write over the cable did. */
enum class WriteResult { OK, UNSUPPORTED, NOT_CONNECTED, FAILED }

/**
 * Operations on the tag on the cable (the shared [UsbTagSession]): history sync, zones, known
 * devices, Bluetooth scan, interval, test reports, re-key and reset. Firmware commands that do not
 * exist yet answer "ERR unknown command": that is reported as [WriteResult.UNSUPPORTED].
 */
object TagOps {
    private val app get() = LibreTagApp.instance
    private fun unknown(lines: List<String>) = lines.any { it.startsWith("ERR unknown") }

    /** `history [from_n]`: sealed reports the tag stored (copy j = 2/3), merged by counter. Returns reports added. */
    suspend fun syncHistory(t: Tracker): Int? {
        val from = (t.positions.filter { it.n >= 0 && it.via != "usb" }.minOfOrNull { it.n } ?: 0L).coerceAtLeast(0)
        // over Bluetooth (0.8.0) a full history takes 5 to 20 s instead of 2: a fast link, more time
        val ble = app.usb.via == Via.BLE
        if (ble) app.usb.fastLink(true)
        val lines = app.usb.session { c ->
            c.port().write((ConsoleCommands.history(from) + "\r").toByteArray())
            c.collect(if (ble) 120_000 else 60_000, 300) { it.startsWith("END") || it.startsWith("ERR") }
        }
        if (ble) app.usb.fastLink(false)
        if (lines == null) return null
        if (unknown(lines) || lines.any { it.startsWith("ERR locked") }) return null
        val recs = ConsoleParsers.parseHistory(lines)
        val added = app.mergeHistory(t.id, recs.map { Triple(it.n, it.j, it.p) })
        DiagLog.i("TagOps", "${t.name}: history ${recs.size} records, $added added")
        app.store.updateTracker(t.id) { it.copy(lastHistorySync = System.currentTimeMillis(), historyAdded = added) }
        return added
    }

    /** Zones as the app has them, written to the tag (`zone clear`, `zone add …`). */
    suspend fun writeZones(t: Tracker): WriteResult {
        if (!app.usb.tagFor(t)) return WriteResult.NOT_CONNECTED
        val r = app.usb.session { c ->
            val clear = c.command(ConsoleCommands.ZONE_CLEAR)
            if (unknown(clear)) return@session WriteResult.UNSUPPORTED
            for (z in t.zones) {
                if (!ConsoleParsers.isOk(c.command(ConsoleCommands.zoneAdd(z.lat, z.lon, z.radiusM)))) return@session WriteResult.FAILED
            }
            WriteResult.OK
        } ?: WriteResult.FAILED
        if (r == WriteResult.OK) app.store.updateTracker(t.id) { it.copy(zonesOnTag = true) }
        return r
    }

    /** Known devices written as anchors (`anchor clear`, `anchor add …`). People: no anchors at all. */
    suspend fun writeKnown(t: Tracker): WriteResult {
        if (!app.usb.tagFor(t)) return WriteResult.NOT_CONNECTED
        if (t.people) return app.usb.session { c ->
            if (c.command(ConsoleCommands.ANCHOR_CLEAR).any { it.startsWith("OK") }) WriteResult.OK else WriteResult.FAILED
        } ?: WriteResult.FAILED
        val r = app.usb.session { c ->
            val list = c.command(ConsoleCommands.ANCHOR_LIST)
            if (unknown(list)) return@session WriteResult.UNSUPPORTED
            // phones paired with blepair live on the tag as IRK anchors: keep them
            val irks = ConsoleParsers.parseAnchors(list).filter { it.kind == "irk" && it.irkHex != null }
            if (!c.command(ConsoleCommands.ANCHOR_CLEAR).any { it.startsWith("OK") }) return@session WriteResult.FAILED
            for (k in irks) c.command(ConsoleCommands.anchorAddIrk(k.irkHex!!, k.address))
            for (d in t.knownDevices.filter { it.kind != "irk" }) {
                val cmd = runCatching {
                    if (d.kind == "name") ConsoleCommands.anchorAddName(d.name) else ConsoleCommands.anchorAddAddress(d.address)
                }.getOrNull() ?: continue
                if (!c.command(cmd).any { it.startsWith("ANCHOR") || it.startsWith("OK") }) return@session WriteResult.FAILED
            }
            WriteResult.OK
        } ?: WriteResult.FAILED
        if (r == WriteResult.OK) app.store.updateTracker(t.id) { it.copy(knownOnTag = true) }
        return r
    }

    /**
     * `blescan <s>`: the tag scans Bluetooth around it; the list comes after [seconds]. Active by
     * default (the firmware's default too): most devices send their name only in a scan response,
     * which a passive scan never asks for. The tag announces itself briefly while doing so.
     */
    suspend fun bleScan(seconds: Int = 30, active: Boolean = true): BleScan? {
        val lines = app.usb.session { c ->
            c.port().write((ConsoleCommands.bleScan(seconds, passive = !active) + "\r").toByteArray())
            // header "blescan: …", the D lines, then END (0.5 firmware: no END, the rows follow the header)
            var header = false
            c.collect((seconds + 8) * 1000, 1500) { l ->
                if (l.startsWith("blescan:")) header = true
                l == "END" || l.startsWith("END ") || l.startsWith("ERR")
            }.also { if (!header) DiagLog.w("TagOps", "blescan: no header") }
        } ?: return null
        return ConsoleParsers.parseBleScan(lines)
    }

    /** Interval and report_every. */
    suspend fun setInterval(min: Int, reportEvery: Int = 1): Boolean = app.usb.session { c ->
        if (!c.set(ConsoleCommands.interval(min))) return@session false
        c.set(ConsoleCommands.reportEvery(reportEvery))
    } ?: false

    /**
     * The tag's reporting settings as the app keeps them (Reporting screen): interval, report_every,
     * listening at checks and parked quiet; anchors by use (People: none, so a phone
     * nearby never silences it). Clears [Tracker.cableDirty] when everything went through.
     */
    suspend fun writeSettings(t: Tracker): WriteResult {
        if (!app.usb.tagFor(t)) return WriteResult.NOT_CONNECTED
        val v = app.usb.connected?.version
        val r = app.usb.session { c ->
            if (!c.set(ConsoleCommands.interval(t.intervalMin))) return@session WriteResult.FAILED
            if (!c.set(ConsoleCommands.reportEvery(t.reportEvery))) return@session WriteResult.FAILED
            if (!c.set(ConsoleCommands.check(t.checkS))) return@session WriteResult.FAILED
            if (!c.set(ConsoleCommands.still(t.stillAfterEff, t.stillHbH))) return@session WriteResult.FAILED
            // 0.10.0: firmware before it does not know the command; nothing else depends on it
            if (app.usb.supports("ackpatience") && !c.set(ConsoleCommands.ackPatience(if (t.reportAtOnce) 2 else 1))) return@session WriteResult.FAILED
            if (t.people && !c.command(ConsoleCommands.ANCHOR_CLEAR).any { it.startsWith("OK") || it.startsWith("ERR unknown") }) return@session WriteResult.FAILED
            WriteResult.OK
        } ?: WriteResult.FAILED
        if (r == WriteResult.OK && !t.people && t.knownDevices.isNotEmpty() && app.usb.supports("anchor")) {
            val k = writeKnown(t)
            if (k != WriteResult.OK) return k
        }
        if (r == WriteResult.OK) app.store.updateTracker(t.id) { it.copy(cableDirty = false, firmware = v ?: it.firmware) }
        return r
    }

    suspend fun testReports(): Boolean = app.usb.command("test 3 300")?.any { it.startsWith("OK test") } ?: false

    /** Anchors as the tag has them (`anchor list`). */
    suspend fun readAnchors(): List<AnchorEntry>? = app.usb.command(ConsoleCommands.ANCHOR_LIST)?.takeIf { !unknown(it) }?.let { ConsoleParsers.parseAnchors(it) }

    /** Zones as the tag has them (`zone list`). */
    suspend fun readZones(): List<Zone>? = app.usb.command(ConsoleCommands.ZONE_LIST)?.takeIf { !unknown(it) }?.let { ConsoleParsers.parseZones(it) }

    /** `blepair`: the firmware pairs with this phone and keeps its IRK (when the firmware has it). */
    suspend fun blePair(): List<String>? = if (app.usb.via == Via.BLE) null else app.usb.session { c ->
        c.port().write(("${ConsoleCommands.BLE_PAIR} 120\r").toByteArray())
        c.collect(125_000, 500) { it == "END" || it.startsWith("ERR") }
    }

    // ---------------------------------------------------------------- Wi-Fi zones (ESP32 tags)

    /** the longest one scan takes (passive scans about 5 s, the firmware gives up at 8 s) */
    private const val SCAN_MAX_MS = 9_000

    /** The console unlock lasts 10 minutes (PROTOCOL.md 6): when it ran out, unlock again with our key. */
    private fun relock(c: TrackerConsole): Boolean {
        val tag = app.usb.connected ?: return false
        // over Bluetooth the handshake is the unlock for the whole session
        if (tag.find != UsbFind.MINE || tag.via == Via.BLE) return false
        val keys = tag.tagId?.let { app.store.get(it) }?.let { app.consoleFor(it) } ?: return false
        return c.unlock(keys.usbKey)
    }

    private fun locked(lines: List<String>) = lines.any { it.startsWith("ERR locked") }

    private fun TrackerConsole.cmd(cmd: String): List<String> =
        command(cmd).let { if (locked(it) && relock(this)) command(cmd) else it }

    /** A long command: its lines as they come ([onLine]) until [done] matched one. */
    private fun TrackerConsole.long(cmd: String, maxMs: Int, onLine: (String) -> Unit, done: (String) -> Boolean): List<String> {
        send(cmd)
        val out = collect(maxMs, 400, onLine, done)
        if (!locked(out) || !relock(this)) return out
        send(cmd)
        return collect(maxMs, 400, onLine, done)
    }

    private fun end(l: String) = l == "END" || l.startsWith("END ") || l.startsWith("ERR")

    /** `wzone list`, kept in the app for the connected tag. Null when the tag did not answer with a list. */
    suspend fun readWifiZones(): List<WifiZoneEntry>? {
        val lines = app.usb.session { c -> c.cmd(WifiZoneCommands.LIST) } ?: return null
        if (unknown(lines) || lines.none { it.startsWith("END") }) return null
        val zones = WifiZoneParsers.parseList(lines)
        val tag = app.usb.connected
        if (tag?.tagId != null && tag.find == UsbFind.MINE) app.usb.storeWifiZones(tag.tagId, zones)
        else app.usb.update { it.copy(wifiZones = zones) }
        return zones
    }

    /**
     * `wzone snap <name> <scans>`: the tag scans [scans] times where it is and keeps the place as a
     * Wi-Fi zone (the same name replaces a zone). [onStep] sees each scan as it ends (IO thread).
     */
    suspend fun snapWifiZone(name: String, scans: Int, onStep: (SnapStep) -> Unit): SnapResult? {
        val cmd = WifiZoneCommands.snap(name, scans)
        val lines = app.usb.session { c ->
            c.long(cmd, scans * SCAN_MAX_MS + 5_000, { l -> WifiZoneParsers.parseSnapStep(l)?.let(onStep) }) {
                it.startsWith("OK wzone") || it.startsWith("ERR")
            }
        } ?: return null
        DiagLog.i("TagOps", "wzone snap: ${lines.count { it.startsWith("SNAP") }} scans, ${lines.lastOrNull { it.startsWith("OK") || it.startsWith("ERR") }}")
        val r = WifiZoneParsers.parseSnap(lines)
        if (r.zone != null) readWifiZones()
        return r
    }

    /** `wzone test <scans>`: scans now and scores every zone, as a wake-up would. Null: no answer. */
    suspend fun testWifiZones(scans: Int = 1): List<WifiTestScan>? {
        val lines = app.usb.session { c -> c.long(WifiZoneCommands.test(scans), scans * SCAN_MAX_MS + 5_000, {}) { end(it) } } ?: return null
        return WifiZoneParsers.parseTest(lines).takeIf { it.isNotEmpty() }
    }

    /** Deletes the zone called [name] (looked up afresh: indexes move when one is deleted). */
    suspend fun deleteWifiZone(name: String): Boolean {
        val ok = app.usb.session { c ->
            val i = WifiZoneParsers.parseList(c.cmd(WifiZoneCommands.LIST)).firstOrNull { it.name == name }?.index ?: return@session false
            ConsoleParsers.isOk(c.cmd(WifiZoneCommands.delete(i)))
        } ?: false
        readWifiZones()
        return ok
    }

    /** The thresholds the connected tag matches with (firmware defaults until `status` printed them). */
    fun wifiThresholds(): WifiThresholds = app.usb.connected?.status?.wifiThr ?: WifiThresholds()

    fun knownFrom(d: BleDevice) = KnownDevice(d.address, d.name ?: "", d.kind.name.lowercase(), d.companyId, System.currentTimeMillis())
}

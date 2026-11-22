package app.libretag.data

import android.content.Context
import app.libretag.mesh.MeshSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Position(
    val fixTime: Long,       // unix s from the tracker (0 = unknown)
    val receivedAt: Long,    // unix ms on the phone
    val hasFix: Boolean,
    val lat: Double,
    val lon: Double,
    val altM: Int,
    val battery: Int?,
    val mode: String,
    val hops: Int?,
    val snrDb: Double,
    /** "mesh" (own radio), "nostr" (public report fetched from a relay) or "usb" (history sync) */
    val via: String = "mesh",
    /** report counter; -1 for history stored before 0.4 */
    val n: Long = -1,
    /** without a fix: the last fix the tracker remembers (GPS time, unix s) */
    val lastFixTime: Long = 0,
    val lastLat: Double = 0.0,
    val lastLon: Double = 0.0,
    /** nearby repeaters, loudest first */
    val neighbours: List<NeighbourRec> = emptyList(),
    val nbTotal: Int = 0,
    val nbFromDiscover: Boolean = false,
    val haccM: Int? = null,
    /** command acknowledged in this report (CMD_ACK) and the low nibble of its seq */
    val cmdAck: Boolean = false,
    val cmdSeq: Int = 0,
    val lowBatt: Boolean = false,
    // ---- SPEC 11 decisions, filled once the protocol carries them (ReportExtras) ----
    /** seconds until the tracker will next be heard (next_in); null = not sent */
    val nextInS: Long? = null,
    /** the last report before an intentional quiet (QUIET flag) */
    val quiet: Boolean = false,
    /** why: "zone", "owner" (owner's devices nearby) or "parked" (0.7, has not moved) */
    val quietReason: String? = null,
    /** 0.7 CHECKS flag: while quiet the tag listens at checks on its configured interval */
    val checks: Boolean = false,
    val energyMah: Int? = null,
    /** BLE summary: devices seen, how many with a stable address */
    val bleDevices: Int? = null,
    val bleStable: Int? = null,
    /** Wi-Fi layout (tags with a Wi-Fi scanner): full seed BSSID + tails, RSSI in dBm */
    val wifi: WifiRec? = null,
    // ---- estimate without GPS, computed on the phone once and kept ----
    val est: EstimateRec? = null,
    // ---- 0.8.1 ----
    /**
     * A bridge drained this report late from a companion node's queue (PROTOCOL.md 4, tag
     * `approx`): [receivedAt] is only the earliest possible time, and this (ms) is when the
     * bridge got it, the latest. null = the time is exact.
     */
    val approxUntil: Long? = null,
    // ---- 0.12 (firmware 0.9.4, PROTOCOL.md 2: BOOT bit) ----
    /** the first report since the tag booted */
    val boot: Boolean = false,
    /** with [boot] and no fix: the reset cause word (`last_reset=`) and the resets in a row */
    val bootCause: String? = null,
    val bootResets: Int? = null,
) {
    /** the receive time is only a range ([receivedAt], [approxUntil]): never anchor a schedule on it */
    val approx: Boolean get() = approxUntil != null
}

@Serializable
data class WifiRec(
    val seed: String,
    val seedRssi: Int,
    val apCount: Int = 0,
    val tails: List<WifiTailRec> = emptyList(),
    /** more full BSSIDs: the report's second seed (no fix time) and the Wi-Fi packet's seed (LOST) */
    val others: List<WifiApRec> = emptyList(),
)

@Serializable
data class WifiApRec(val mac: String, val rssi: Int)

@Serializable
data class WifiTailRec(val tail: String, val rssi: Int)

/** Bluetooth devices the tag reported after a BLE_SCAN command (PROTOCOL.md 2.3). */
@Serializable
data class BleScanRec(val at: Long, val n: Long, val devices: List<BleSeenRec>, val total: Int = devices.size)

@Serializable
data class BleSeenRec(val address: String, val kind: String, val rssi: Int, val name: String? = null, val company: Int? = null, val anchor: Boolean = false)

@Serializable
data class NeighbourRec(val hash: String, val snrDb: Double)

/** Area estimate from repeaters (68 % / 95 % radii), or why there is none. */
@Serializable
data class EstimateRec(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val r68: Double = 0.0,
    val r95: Double = 0.0,
    /** "ok", "ambiguous" (repeater code shared abroad), "nodb" (no repeater map), "none" (nothing heard / not on the map) */
    val state: String = "ok",
    val anchors: List<AnchorRec> = emptyList(),
    /** repeater map version (download time) the estimate was made with */
    val dbAt: Long = 0,
)

@Serializable
data class AnchorRec(val name: String, val lat: Double, val lon: Double, val snrDb: Double, val hash: String = "")

/** A command on its way to a tag: the "slip" the UI shows (waiting, sent, confirmed, not confirmed). */
@Serializable
data class PendingCommand(
    val seq: Int,
    val label: String,
    val body: String,        // hex of the command plaintext
    val sentForN: Long? = null,
    /** CommandKind name: LOST, TRACK, BEEP, NORMAL */
    val kind: String = "",
    val queuedAt: Long = 0,
    val sentAt: Long? = null,
    /** a report after the one we answered came without the acknowledgement: retried with the next one */
    val notConfirmed: Boolean = false,
    /** 0.7: sent at this check k after report [sentForN] (null = right after the report) */
    val sentForCheck: Int? = null,
    /** 0.8.0: the mailbox group tag of this command (16 lowercase hex, random) */
    val group: String = "",
    /** 0.11.2: Nostr mailbox entries are published (false: only local ways, mesh.CommandRoute) */
    val mailbox: Boolean = true,
    /** 0.11.2: the way the app expects it to arrive by (CommandRoute.Via name), "" for the old mix */
    val via: String = "",
    /** 0.11.2: when the tag should take it (ms), 0 when unknown */
    val expectAt: Long = 0,
)

/**
 * When the tag checks during the current quiet stretch (0.7, docs/design/checks-0.7.md
 * "Schedule"): after report [n], received at [t0] (ms), checks at t0 + offsetsS[k - 1] s.
 */
@Serializable
data class CheckPlan(val n: Long, val t0: Long, val intervalMin: Int, val offsetsS: List<Long>) {
    /** check k (1-based) and its time (ms) for the first check after [now], or null */
    fun next(now: Long): Pair<Int, Long>? {
        offsetsS.forEachIndexed { i, o -> val at = t0 + o * 1000; if (at > now) return (i + 1) to at }
        return null
    }
    fun at(k: Int): Long? = offsetsS.getOrNull(k - 1)?.let { t0 + it * 1000 }
}

/** The last command that finished (for the slip "Beep · confirmed 14:32"). */
@Serializable
data class CommandResult(val kind: String, val seq: Int, val confirmedAt: Long, val sentAt: Long? = null)

/**
 * A GPS place ("zone" in code and on the tag). [notifyLeave] / [notifyArrive]: tell the owner when
 * the tag leaves or arrives here; null follows the tag type's default ([Places.defaultNotify]),
 * so changing the type later moves it. An explicit choice sticks.
 */
@Serializable
data class Zone(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val notifyLeave: Boolean? = null,
    val notifyArrive: Boolean? = null,
)

/** A Bluetooth device near which the tag stays quiet (SPEC 11.2). */
@Serializable
data class KnownDevice(
    val address: String,
    val name: String = "",
    /** "public", "static", "rpa", "nrpa", "irk" */
    val kind: String = "public",
    val companyId: Int? = null,
    val addedAt: Long = 0,
)

@Serializable
data class AlertPrefs(
    val silent: Boolean = true,
    val lowBattery: Boolean = true,
    val leftZone: Boolean = true,
    val guard: Boolean = true,
)

/** Someone we gave access to (we can't revoke what they already have). */
@Serializable
data class ShareRec(val who: String, val control: Boolean, val since: Long, val epoch: Long, val admin: Boolean = false)

@Serializable
data class Tracker(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** K0 (64 hex); empty for a tag shared with us */
    val keyHex: String,
    val lastN: Long = -1,
    val cmdSeq: Int = 0,
    val pending: PendingCommand? = null,
    val lastCommand: CommandResult? = null,
    val lowBattNotified: Boolean = false,
    val positions: List<Position> = emptyList(),
    /** reporting interval set at pairing (for the "tag is silent" alarm) */
    val intervalMin: Int = 60,
    /** guard: alarm when a fix lands farther than GUARD_M from this point */
    val guardLat: Double? = null,
    val guardLon: Double? = null,
    /** when the guard spot was set (unix s; null for guards set before 0.7.1) */
    val guardSince: Long? = null,
    val guardAlerted: Boolean = false,
    val silentAlerted: Boolean = false,
    val lowBattDmSent: Boolean = false,
    // ---- 0.6 ----
    /** "person", "thing", "vehicle" */
    val type: String = "thing",
    /** trail blaze: colour (red, blue, green, yellow) and symbol (stripe, diag, triangle, circle, square) */
    val markColor: String = "",
    val markSymbol: String = "",
    val zones: List<Zone> = emptyList(),
    /** zones as the tag has them (written over USB), to tell "saved here" from "on the tag" */
    val zonesOnTag: Boolean = false,
    val knownDevices: List<KnownDevice> = emptyList(),
    val knownOnTag: Boolean = false,
    val alerts: AlertPrefs = AlertPrefs(),
    /**
     * The one latched alarm: "guard:<ms>" (moved while guarded). Leaving a place is an event
     * ([leftPlace]); an old "zone:<name>:<ms>" is converted on load ([Places.migrate]).
     */
    val alarm: String? = null,
    /** zone the last fix was inside (for "left Home") */
    val inZone: String? = null,
    /** until when TRACK / LOST runs (ms), from the confirmed command */
    val modeUntil: Long? = null,
    val modeIntervalS: Int? = null,
    val shares: List<ShareRec> = emptyList(),
    /** a tag someone shared with us: their name, and the key chain from an epoch */
    val sharedBy: String? = null,
    val sharedControl: Boolean = false,
    val sharedEpoch: Long = 0,
    val sharedViewHex: String = "",
    val sharedCmdHex: String = "",
    /** full access (0.13.3): the tag's usb_key and id (hex), which open its console; empty otherwise */
    val sharedUsbHex: String = "",
    val sharedIdHex: String = "",
    /** what the tag said over USB */
    val firmware: String? = null,
    val board: String? = null,
    val lastUsbAt: Long = 0,
    val lastHistorySync: Long = 0,
    val historyAdded: Int = 0,
    val createdAt: Long = 0,
    /** the newest Bluetooth surroundings the tag reported over the mesh */
    val bleScan: BleScanRec? = null,
    /** Wi-Fi lookups (cached position per report) */
    val wifiFixes: Map<Long, WifiFixRec> = emptyMap(),
    // ---- 0.7 ----
    /** "things" (quiet with your phone near) or "people" (only home zones); empty = from [type] */
    val use: String = "",
    /** listening at checks, seconds (0 = off) */
    val checkS: Int = DEFAULT_CHECK_S,
    /** parked quiet: after this many reports in one place (0 = off, null = the default for [useKind]) */
    val stillAfter: Int? = null,
    /** parked quiet: heartbeat in hours */
    val stillHbH: Int = DEFAULT_STILL_HB_H,
    /** settings above (and the anchors for [useKind]) still have to be written over the cable */
    val cableDirty: Boolean = false,
    /** checks of the current quiet stretch, from the last quiet report with CHECKS */
    val checkPlan: CheckPlan? = null,
    // ---- 0.7.3 ----
    /** report at every n-th wake-up ([intervalMin] apart), only listen for commands at the others */
    val reportEvery: Int = 1,
    /** quiet heartbeats as the tag reported them over USB (firmware defaults until read) */
    val zoneHbH: Int = HB_ZONE_H,
    val ownerHbH: Int = HB_OWNER_H,
    /** when the settings above were last read from the tag over USB and differed from the app's */
    val settingsReadAt: Long = 0,
    // ---- 0.7.5 ----
    /** when a relay last accepted the tag's welcome DM to the owner (ms); null = never */
    val welcomeSentAt: Long? = null,
    /** the app offered to send the missing welcome DM once (whatever the answer) */
    val welcomeOffered: Boolean = false,
    // ---- 0.8.0 ----
    /** the "battery almost empty" (10 %) notification went out for the current crossing */
    val critBattNotified: Boolean = false,
    /** when the battery last read above the reset level (ms): older 10 % DMs belong to a past crossing */
    val critBattResetAt: Long = 0,
    // ---- 0.8.6 ----
    /** the tag scans Wi-Fi (an ESP32 board): its home zones are Wi-Fi zones, taken on the spot over the cable */
    val wifiTag: Boolean = false,
    /** Wi-Fi zones as last read from the tag (`wzone list`); the tag keeps the fingerprints */
    val wifiZones: List<WifiZoneRec> = emptyList(),
    /** when [wifiZones] was read (ms), 0 = never */
    val wifiZonesAt: Long = 0,
    /** [inZone] is a Wi-Fi zone: leaving is the end of the tag's quiet, not a fix outside */
    val inZoneWifi: Boolean = false,
    // ---- 0.9 (places) ----
    /**
     * The place the tag last left ([WifiZoneTransit.UNNAMED] for one of several Wi-Fi places) and
     * when (ms). An event, not an alarm: cleared when the tag is at a place again. The UI shows it
     * for [Places.LEFT_SHOW_MS] at most ([leftRecently]).
     */
    val leftPlace: String? = null,
    val leftAt: Long? = null,
    /** data migrations applied ([Places.migrate]); 0 = written before places had notify choices */
    val placesV: Int = 0,
    // ---- 0.12 (docs/design/tag-diagnosis.md 3) ----
    /** the tag's health as `status` printed it over USB or the Bluetooth console */
    val health: TagHealth? = null,
    /** the last advert of this tag the phone heard (console window or, later, "here") */
    val bleSight: BleSightRec? = null,
    /** times of distinct console windows the tag opened by itself (a new salt), newest last, at most 6 */
    val bleBoots: List<Long> = emptyList(),
    /** when a firmware update for this tag started on this phone (ms) */
    val dfuStartedAt: Long? = null,
    /** boots learned from reports (BOOT bit) or counter gaps, newest last, at most 6 */
    val bootRecs: List<BootRec> = emptyList(),
    /** when a report opened with a counter that went back (ms) */
    val counterBackAt: Long? = null,
    /** "Check if it's here": when the app asked over Bluetooth and the tag did not answer by its wake-up (ms) */
    val notHereAt: Long? = null,
    /** "Check if it's here" running: when the app expects the tag's wake-up (ms) */
    val hereAskAt: Long? = null,
    /** the last restart episode the owner was told about (ms of its first boot) */
    val restartNotified: Long? = null,
    /** an UPDATE_UNFINISHED notice went out for [dfuStartedAt] */
    val dfuNotified: Boolean = false,
) {
    /** how often the tag reports in IDLE (min): the wake-up interval times [reportEvery] */
    val reportPeriodMin: Int get() = intervalMin * reportEvery.coerceAtLeast(1)
    val useKind: String get() = use.ifEmpty { defaultUse(type) }
    val people: Boolean get() = useKind == USE_PEOPLE
    val stillAfterEff: Int get() = stillAfter ?: if (people) 0 else DEFAULT_STILL_AFTER
    /** the app knows the tag's IDLE interval (it set it); a shared tag's is unknown */
    val intervalKnown: Boolean get() = !isShared || (sharedAdmin && settingsReadAt > 0)

    /** the last report heard (fixes the tag kept while quiet were never sent) */
    val last: Position? get() = positions.lastOrNull { it.via != "usb-quiet" }
    val lastFix: Position? get() = positions.lastOrNull { it.hasFix }
    val isShared: Boolean get() = sharedBy != null
    val canCommand: Boolean get() = !isShared || sharedControl
    /** shared with full access: the console over USB and Bluetooth, like the owner's phone */
    val sharedAdmin: Boolean get() = isShared && sharedUsbHex.isNotEmpty()
    /** this phone can open the tag's console: the owner (K0) or a full-access share */
    val hasConsole: Boolean get() = if (isShared) sharedAdmin else keyHex.isNotEmpty()

    /** a guard spot is armed */
    val guarding: Boolean get() = guardLat != null && guardLon != null
    /** the guard fired and nobody re-armed or stopped it yet */
    val guardAlarm: Boolean get() = alarm?.startsWith(Places.ALARM_GUARD) == true

    /**
     * Whether leaving ([leave] = true) or arriving at [place] notifies: the place's own choice, else
     * the default for [type]. [place] = [WifiZoneTransit.UNNAMED] (a report from one of several
     * Wi-Fi places, it can't say which): true when any Wi-Fi place of the tag notifies.
     * Unknown names (a place deleted since) follow the type default.
     */
    fun notifies(place: String, leave: Boolean): Boolean {
        val def = Places.defaultNotify(type)
        fun pick(l: Boolean?, a: Boolean?) = (if (leave) l else a) ?: def
        if (place == WifiZoneTransit.UNNAMED) {
            return if (wifiZones.isEmpty()) def else wifiZones.any { pick(it.notifyLeave, it.notifyArrive) }
        }
        zones.firstOrNull { it.name == place }?.let { return pick(it.notifyLeave, it.notifyArrive) }
        wifiZones.firstOrNull { it.name == place }?.let { return pick(it.notifyLeave, it.notifyArrive) }
        return def
    }

    /** a departure the UI still shows (the neutral LEFT state): under [Places.LEFT_SHOW_MS] old, not at a place again */
    fun leftRecently(now: Long): Boolean =
        leftPlace != null && leftAt != null && inZone == null && now - leftAt in 0 until Places.LEFT_SHOW_MS

    /**
     * Guard this spot: the newest fix ([lastFix]), alarm cleared. Also "Guard the new spot" after a
     * guard alarm. Without a fix it only clears the alarm (the old spot, if any, stays).
     */
    fun guardHere(nowMs: Long): Tracker {
        val fix = lastFix
        val cleared = copy(guardAlerted = false, alarm = alarm?.takeIf { !it.startsWith(Places.ALARM_GUARD) })
        return if (fix == null) cleared else cleared.copy(guardLat = fix.lat, guardLon = fix.lon, guardSince = nowMs / 1000)
    }

    /** Stop guarding (also "Stop guarding" after a guard alarm). */
    fun guardOff(): Tracker = copy(guardLat = null, guardLon = null, guardSince = null, guardAlerted = false,
        alarm = alarm?.takeIf { !it.startsWith(Places.ALARM_GUARD) })

    companion object {
        const val USE_THINGS = "things"
        const val USE_PEOPLE = "people"
        const val DEFAULT_CHECK_S = 30
        /** Reporting offers these (0 = off); firmware 0.7.2 takes 0..180, older 0..60 */
        val CHECK_CHOICES_S = listOf(0, 30, 60, 120)
        const val DEFAULT_STILL_AFTER = 3
        const val DEFAULT_STILL_HB_H = 6
        /** heartbeats of a quiet tag (firmware defaults, PROTOCOL.md 2.1) */
        const val HB_ZONE_H = 24
        const val HB_OWNER_H = 8
        /** a person is protected against abduction: a phone nearby must not silence the tag */
        fun defaultUse(type: String) = if (type == "person") USE_PEOPLE else USE_THINGS
    }
}

/**
 * A Wi-Fi place on the tag: its name, how many networks it holds, from how many scans. The notify
 * choices are the app's own (as in [Zone]); [Places.carryNotify] keeps them when the list is read again.
 */
@Serializable
data class WifiZoneRec(
    val name: String,
    val aps: Int,
    val scans: Int,
    val notifyLeave: Boolean? = null,
    val notifyArrive: Boolean? = null,
)

/** Places (zones): notify defaults, the data migration, the alarm format. Pure, unit tested. */
object Places {
    /** a departure stays visible (LEFT state) this long unless the tag is at a place again */
    const val LEFT_SHOW_MS = 12 * 3600_000L
    const val ALARM_GUARD = "guard"
    private const val ALARM_ZONE = "zone:"
    /** the current [Tracker.placesV] */
    const val VERSION = 1

    /** a person: tell on leaving and arriving; a thing or a vehicle: neither (Guard is for "must not move") */
    fun defaultNotify(type: String): Boolean = type == "person"

    fun guardAlarm(atMs: Long) = "$ALARM_GUARD:$atMs"

    /** "zone:<name>:<ms>" (written before 0.9) as (name, ms); the name may hold ':' */
    fun parseZoneAlarm(a: String?): Pair<String, Long?>? {
        if (a == null || !a.startsWith(ALARM_ZONE)) return null
        val rest = a.removePrefix(ALARM_ZONE)
        val i = rest.lastIndexOf(':')
        return if (i < 0) rest to null else rest.substring(0, i) to rest.substring(i + 1).toLongOrNull()
    }

    /**
     * Brings a tag to [VERSION]. Always: an old latched "zone:<name>:<ms>" alarm becomes the
     * [Tracker.leftPlace] event. Once (placesV 0 -> 1): a tag whose "Left a zone" alert was off
     * gets notifyLeave = false on every place, so nothing starts notifying after the update.
     * Idempotent; returns the same instance when nothing changed.
     */
    fun migrate(t: Tracker): Tracker {
        var out = t
        parseZoneAlarm(out.alarm)?.let { (name, at) ->
            out = out.copy(alarm = null, leftPlace = out.leftPlace ?: name, leftAt = out.leftAt ?: at)
        }
        if (out.placesV < 1) {
            if (!out.alerts.leftZone) out = out.copy(
                zones = out.zones.map { it.copy(notifyLeave = it.notifyLeave ?: false) },
                wifiZones = out.wifiZones.map { it.copy(notifyLeave = it.notifyLeave ?: false) },
            )
            out = out.copy(placesV = VERSION)
        }
        return if (out == t) t else out
    }

    /** Wi-Fi places as just read from the tag, with the notify choices the app had for the same names. */
    fun carryNotify(old: List<WifiZoneRec>, fresh: List<WifiZoneRec>): List<WifiZoneRec> = fresh.map { f ->
        val o = old.firstOrNull { it.name == f.name } ?: return@map f
        f.copy(notifyLeave = f.notifyLeave ?: o.notifyLeave, notifyArrive = f.notifyArrive ?: o.notifyArrive)
    }
}

@Serializable
data class WifiFixRec(val lat: Double, val lon: Double, val r68: Double, val r95: Double, val at: Long, val service: String)

/** Trackers and their history, kept in SharedPreferences as JSON. */
class TrackerStore(context: Context) : MeshSettings {
    private val prefs = context.getSharedPreferences("libretag", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _trackers = MutableStateFlow(load())
    val trackers: StateFlow<List<Tracker>> = _trackers.asStateFlow()

    private fun load(): List<Tracker> = try {
        val stored = prefs.getString("trackers", null)?.let { json.decodeFromString<List<Tracker>>(it) } ?: emptyList()
        // 0.8.0: K0 and shared keys are wrapped with the Keystore key; plain ones from before are rewritten
        val plainLeft = stored.any { app.libretag.keys.Secrets.trackerNeedsWrap(it) }
        val opened = stored.map { app.libretag.keys.Secrets.openTracker(it) }
        val list = migrate(opened)
        // 0.9 places: write a migrated tag back, so the one-time step is recorded
        val placesChanged = list.indices.any { list[it].placesV != opened[it].placesV || list[it].alarm != opened[it].alarm }
        if ((plainLeft || placesChanged) && app.libretag.keys.Secrets.box != null) persist(list)
        list
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(list: List<Tracker>) {
        prefs.edit().putString("trackers", json.encodeToString(list.map { app.libretag.keys.Secrets.sealTracker(it) })).apply()
    }

    /** 0.6: every tag gets a trail blaze (first free colour and symbol). 0.9: [Places.migrate]. */
    private fun migrate(list: List<Tracker>): List<Tracker> {
        var out = list.map { Places.migrate(it) }
        for (i in out.indices) {
            if (out[i].markColor.isEmpty() || out[i].markSymbol.isEmpty()) {
                val m = Marks.free(out.filterIndexed { j, _ -> j != i }.map { it.markColor to it.markSymbol })
                out = out.toMutableList().also { it[i] = it[i].copy(markColor = m.first, markSymbol = m.second) }
            }
        }
        return out
    }

    @Synchronized
    fun update(transform: (List<Tracker>) -> List<Tracker>) {
        val next = transform(_trackers.value)
        _trackers.value = next
        persist(next)
    }

    fun updateTracker(id: String, transform: (Tracker) -> Tracker) =
        update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun get(id: String): Tracker? = _trackers.value.firstOrNull { it.id == id }

    /** a new tag (paired, restored from a backup) comes in at the current data version */
    fun add(t: Tracker) = update { it + Places.migrate(t) }

    /** USB pairing: a tracker already in the list keeps its history and only gets the new settings. */
    fun addOrUpdate(keyHex: String, make: () -> Tracker, change: (Tracker) -> Tracker): String {
        var id = ""
        update { list ->
            val cur = list.firstOrNull { it.keyHex.equals(keyHex, ignoreCase = true) }
            if (cur != null) {
                id = cur.id
                list.map { if (it.id == cur.id) change(it) else it }
            } else {
                val t = Places.migrate(make())
                id = t.id
                list + t
            }
        }
        return id
    }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    fun restore(t: Tracker, index: Int) = update { list ->
        if (list.any { it.id == t.id }) list else list.toMutableList().apply { add(index.coerceIn(0, size), t) }
    }

    /** Relays for public reports (trackers out of reach of your own nodes). */
    var relays: List<String>
        get() = prefs.getString("relays", null)?.let { json.decodeFromString<List<String>>(it) }
            // 0.5.3: damus replaced by our own relay (which accepts kinds 4243/4244 from any key)
            ?.map { if (it.trimEnd('/') == "wss://relay.damus.io") "wss://nostr.cypherpunk.today" else it }?.distinct()
            ?: DEFAULT_RELAYS
        set(v) { prefs.edit().putString("relays", json.encodeToString(v)).apply() }

    /** 0.8.0 private report query: up to when (unix s) every kind 4243 was fetched */
    var reportSyncAt: Long
        get() = prefs.getLong("reportSyncAt", 0)
        set(v) { prefs.edit().putLong("reportSyncAt", v).apply() }
    /** until when (ms) the app uses the old per-tag window query because the network is too busy */
    var reportWindowUntil: Long
        get() = prefs.getLong("reportWindowUntil", 0)
        set(v) { prefs.edit().putLong("reportWindowUntil", v).apply() }

    /** when the silent-tag check last ran (ms), from the app loop or the backup alarm (Settings shows it) */
    var lastSilenceCheck: Long
        get() = prefs.getLong("lastSilenceCheck", 0)
        set(v) { prefs.edit().putLong("lastSilenceCheck", v).apply() }

    // MeshSettings
    /** The owner's npub, offered to every tracker paired over USB (commands by Nostr DM). */
    var ownerNpub: String?
        get() = prefs.getString("ownerNpub", null)
        set(v) { prefs.edit().putString("ownerNpub", v).apply() }

    override val meshUrl: String get() = prefs.getString("meshUrl", null) ?: "ws://127.0.0.1:8787/api/v1"
    override var meshIdentity: String?
        get() = prefs.getString("meshIdentity", null)
        set(v) { prefs.edit().putString("meshIdentity", v).apply() }
    override val meshRegion: String get() = prefs.getString("meshRegion", null) ?: ""
    override var meshCursor: String?
        get() = prefs.getString("meshCursor", null)
        set(v) { prefs.edit().putString("meshCursor", v).apply() }

    companion object {
        const val MAX_HISTORY = 1000
        val DEFAULT_RELAYS = listOf("wss://nostr.cypherpunk.today", "wss://relay.primal.net", "wss://nos.lol")
    }
}

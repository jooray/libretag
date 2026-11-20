package app.libretag.data

import app.libretag.locate.Geo
import kotlin.math.abs

/** The own MeshCore node: never set up, connected, or set up and not connected now. */
enum class MeshLink { NEVER, UP, DOWN }

/** What is on the USB cable, attributed to one tag (usb/CableClassify). */
enum class Cable { NONE, TAG, BOOTLOADER, ESP_DOWNLOAD }

/**
 * A known bridge heard a report of this tag newer than the newest the app opened (kind 4245 or
 * 4243 for a counter above lastN, docs/design/tag-diagnosis.md 4.4).
 */
data class ReceiptSeen(
    val atMs: Long,
    val n: Long,
    /** hop class as the receipt says ("0", "1-3", "4+"), null = not stated */
    val hops: String? = null,
    /** the first relay of that bridge's announcement, offered by "Add relay" */
    val relay: String? = null,
    /** the bridge had receipts for this tag on at least two different days (owner decision 8) */
    val trusted: Boolean = false,
    /** the app tried to fetch the report from the bridge's relays and did not get it */
    val fetchFailed: Boolean = true,
)

/** Everything the diagnosis knows besides the tag (docs/design/tag-diagnosis.md 3). */
data class DiagContext(
    val online: Boolean = true,
    val relaysUp: Int = 1,
    val relaysAll: Int = 1,
    val mesh: MeshLink = MeshLink.NEVER,
    /** the last time the own node was connected (ms), null = never */
    val meshUpAt: Long? = null,
    /** a receive link is up right now (a relay or the own node), backfill or not */
    val linkUp: Boolean = true,
    /** since when a receive path works with the missed reports fetched (ms); null = the phone is blind */
    val pathUpSince: Long? = 0,
    /** the start of the last stretch without a path (ms), null = none known */
    val blindFrom: Long? = null,
    /** when the relays last answered the query for the tags' windows (ms) */
    val backfillAt: Long? = null,
    /** what is on the cable and is this tag */
    val cable: Cable = Cable.NONE,
    val receipt: ReceiptSeen? = null,
    /** the phone's clock against a trusted time source (ms), null = unknown */
    val clockSkewMs: Long? = null,
    val minFirmware: String = "0.9.0",
    /** Bluetooth is on and allowed: "Check if it's here" can be offered */
    val bluetooth: Boolean = true,
) {
    val blind: Boolean get() = pathUpSince == null
    /** the phone could not receive at some point after [since] */
    fun wasBlind(since: Long): Boolean = pathUpSince == null || pathUpSince > since
}

/**
 * Why a tag is late: the headline cause, its level and confidence, the facts the copy needs, and
 * the actions that settle it. Pure (docs/design/tag-diagnosis.md 5); the list, the detail, the
 * map and the notification read the same value.
 */
object Diagnosis {
    enum class Level { CALM, NOTE, WARN }
    enum class Confidence { HIGH, MEDIUM, LOW, NONE }

    enum class Cause(val level: Level) {
        BOOTLOADER(Level.NOTE), ESP_DOWNLOAD(Level.NOTE), TX_FLOOR(Level.NOTE), RESET_LOOP(Level.WARN),
        ON_CABLE(Level.CALM), COUNTER_AHEAD(Level.NOTE), FIRMWARE_TOO_OLD(Level.NOTE), PHONE_OFFLINE(Level.NOTE),
        RELAYS_DOWN(Level.NOTE), JUST_BACK(Level.NOTE), NOT_DELIVERED(Level.NOTE), WITH_YOU(Level.CALM),
        RESTARTED_HERE(Level.NOTE), RESTARTING(Level.WARN), UPDATE_UNFINISHED(Level.WARN), BATTERY_EMPTY(Level.WARN),
        SCHEDULE_PENDING(Level.NOTE), SCHEDULE_MISMATCH(Level.NOTE), OUT_OF_RANGE(Level.WARN), NODE_DOWN(Level.NOTE),
        MAYBE_WITH_YOU(Level.WARN), MAYBE_QUIET_PLACE(Level.WARN), AFTER_CABLE(Level.WARN), PHONE_CLOCK(Level.NOTE),
        UNKNOWN(Level.WARN),
    }

    enum class Action {
        FINISH_UPDATE, UPDATE_FIRMWARE, HOW_TO_RESTART, RESTART_ESP, RESTART_CONSOLE, READ_LOG, OPEN_BLE,
        CHECK_HERE, SEND_REPORT, CONNECTIONS, TRY_AGAIN, ADD_RELAY, USE_TAG_SCHEDULE, SEND_MINE, CATCH_UP,
        DATE_SETTINGS, OPEN_MAP, SERVICE,
    }

    /** The numbers and names a cause's sentences need; which ones are set depends on the cause. */
    data class Facts(
        val at: Long? = null,
        val at2: Long? = null,
        val times: List<Long> = emptyList(),
        val count: Int? = null,
        val word: String? = null,
        val name: String? = null,
        val periodS: Int? = null,
        val period2S: Int? = null,
        val version: String? = null,
        val deadline: Long? = null,
    )

    data class Result(
        val cause: Cause,
        val confidence: Confidence,
        val facts: Facts = Facts(),
        val actions: List<Action> = emptyList(),
    ) {
        val level: Level get() = cause.level
    }

    /** "Check if it's here" not answered: kept this long (one period at least) */
    const val NOT_HERE_KEEP_MIN_MS = 3600_000L
    const val RESTART_WINDOW_MS = 3600_000L
    const val BOOTS_WINDOW_MS = 6 * 3600_000L
    const val RESTARTED_HERE_MS = 30 * 60_000L
    const val JUST_BACK_MS = 2 * 60_000L
    const val CLOCK_SKEW_MS = 2 * 60_000L
    const val COUNTER_WINDOW = 2048L
    const val EMPTY_PCT = 5
    const val STOPS_PCT = 7
    const val ZONE_EDGE_M = 50.0

    /**
     * A tag is late: its expected report time has passed (or, never heard, a period since
     * pairing). Inside the schedule, a QUIET report inside its heartbeat: not late, no diagnosis.
     */
    fun late(t: Tracker, now: Long): Boolean {
        val next = Status.nextAt(t, now)
        if (next == null) return t.lastN < 0 && t.createdAt > 0 && now - t.createdAt > t.reportPeriodMin * 60_000L
        return now > next
    }

    /**
     * The headline cause for a late tag, first match in the order of the design (5), or null when
     * the tag is not late. A cause at level WARN is shown only once the status is SILENT; NOTE and
     * CALM show as soon as the report is late ([shown]).
     */
    fun of(t: Tracker, s: TagStatus, c: DiagContext, now: Long): Result? {
        if (!late(t, now)) return null
        val p = t.last
        val after = p?.receivedAt ?: t.createdAt
        val h = t.health?.takeIf { it.fresh(now) || it.at > after }
        val healthFresh = t.health?.fresh(now) == true

        // 1, 2: the cable says it outright
        if (c.cable == Cable.BOOTLOADER) return Result(Cause.BOOTLOADER, Confidence.HIGH, actions = listOf(Action.FINISH_UPDATE, Action.HOW_TO_RESTART))
        if (c.cable == Cable.ESP_DOWNLOAD) return Result(Cause.ESP_DOWNLOAD, Confidence.HIGH, actions = listOf(Action.RESTART_ESP))
        // 3, 4: what the tag says about itself
        if ((c.cable == Cable.TAG || healthFresh) && t.health?.txOk == false)
            return Result(Cause.TX_FLOOR, Confidence.HIGH)
        val hh = t.health
        if (healthFresh && hh != null && (hh.resets ?: 0) >= 2 && hh.faultReset)
            return Result(Cause.RESET_LOOP, Confidence.HIGH, Facts(count = hh.resets, word = hh.lastReset, periodS = hh.bootDelayS),
                listOf(Action.READ_LOG, Action.RESTART_CONSOLE))
        // 5
        if (c.cable == Cable.TAG) return Result(Cause.ON_CABLE, Confidence.HIGH, Facts(at = p?.receivedAt, deadline = Status.nextAt(t, now)),
            listOf(Action.SEND_REPORT))
        // the tag counts far ahead of the app's window: its reports can't be found
        val hn = hh?.n
        if (hn != null && t.lastN >= 0 && hn - t.lastN > COUNTER_WINDOW)
            return Result(Cause.COUNTER_AHEAD, Confidence.HIGH, actions = listOf(Action.CATCH_UP))
        // 6
        val fw = t.firmware
        if (fw != null && app.libretag.usb.ConsoleParsers.isOlder(fw, c.minFirmware))
            return Result(Cause.FIRMWARE_TOO_OLD, Confidence.HIGH, Facts(version = fw, word = c.minFirmware), listOf(Action.UPDATE_FIRMWARE))
        // 7 to 9: the phone's own blindness is not the tag's silence
        if (!c.online && c.mesh != MeshLink.UP)
            return Result(Cause.PHONE_OFFLINE, Confidence.HIGH, Facts(at = p?.receivedAt), listOf(Action.CONNECTIONS))
        if (c.online && c.relaysUp == 0 && c.relaysAll > 0 && c.mesh != MeshLink.UP)
            return Result(Cause.RELAYS_DOWN, Confidence.HIGH, Facts(at = p?.receivedAt, count = c.relaysAll), listOf(Action.CONNECTIONS, Action.TRY_AGAIN))
        val expected = Status.nextAt(t, now) ?: after
        if (c.linkUp && c.wasBlind(expected) && (c.backfillAt == null || c.blind || now - c.backfillAt < JUST_BACK_MS))
            return Result(Cause.JUST_BACK, Confidence.HIGH, Facts(at = c.blindFrom, at2 = c.pathUpSince ?: now))
        // never heard: nothing below applies to a tag without a report
        if (p == null) return Result(Cause.UNKNOWN, Confidence.NONE)
        // 10: a bridge heard a newer report
        c.receipt?.takeIf { it.n > t.lastN && it.fetchFailed }?.let { r ->
            return Result(Cause.NOT_DELIVERED, Confidence.HIGH, Facts(at = r.atMs, word = r.hops, name = r.relay),
                listOfNotNull(Action.ADD_RELAY.takeIf { r.trusted && r.relay != null }, Action.CONNECTIONS))
        }
        // 11: the phone and the tag see each other, or the tag said why it is quiet
        val ownerMs = t.ownerHbH * 3600_000L + Status.SILENT_MARGIN_MS
        val sight = t.bleSight
        if (sight != null && sight.kind == "here" && sight.at > after && now - sight.at < ownerMs)
            return Result(Cause.WITH_YOU, Confidence.HIGH, Facts(at = sight.at, deadline = sight.at + t.ownerHbH * 3600_000L), listOf(Action.OPEN_BLE))
        if (hh != null && hh.quiet == "owner" && hh.at > after && now - hh.at < ownerMs)
            return Result(Cause.WITH_YOU, Confidence.HIGH, Facts(at = hh.at, deadline = hh.at + t.ownerHbH * 3600_000L), listOf(Action.OPEN_BLE))
        // 12, 13: restarts
        val bootsHour = t.bleBoots.filter { now - it in 0..RESTART_WINDOW_MS }
        val last = t.bleBoots.lastOrNull()
        if (bootsHour.size == 1 && last != null && last > after && now - last < RESTARTED_HERE_MS)
            return Result(Cause.RESTARTED_HERE, Confidence.HIGH, Facts(at = last), listOf(Action.OPEN_BLE))
        restarting(t, now)?.let { return it }
        // 14
        val dfu = t.dfuStartedAt
        if (dfu != null && dfu > after && c.cable == Cable.NONE)
            return Result(Cause.UPDATE_UNFINISHED, Confidence.MEDIUM, Facts(at = dfu), listOf(Action.HOW_TO_RESTART))
        // 15
        batteryEmpty(t, now)?.let { return it }
        // 16, 17: the schedule
        schedulePending(t, now)?.let { return it }
        scheduleMismatch(t, now)?.let { return it }
        // 18
        if (outOfRange(t)) return Result(Cause.OUT_OF_RANGE, Confidence.LOW, Facts(periodS = t.reportPeriodMin * 60), listOf(Action.OPEN_MAP))
        // the own node is down and this tag's reports came only through it
        if (nodeDown(t, c, now)) return Result(Cause.NODE_DOWN, Confidence.MEDIUM, Facts(at = c.meshUpAt), listOf(Action.CONNECTIONS))
        // 19 to 21: guesses; they change the sentence and the action, never the level
        val here = checkHereOffered(t, c, now)
        val notHere = t.notHereAt?.let { now - it < maxOf(NOT_HERE_KEEP_MIN_MS, t.reportPeriodMin * 60_000L) } == true
        val zone = p?.let { zoneNear(t, it) }
        if (!notHere && (t.knownOnTag || t.knownDevices.isNotEmpty()) && !t.people && zone == null && p?.quiet != true)
            return Result(Cause.MAYBE_WITH_YOU, Confidence.LOW, Facts(deadline = nextWake(t, now)), listOfNotNull(Action.CHECK_HERE.takeIf { here }))
        if (zone != null && p?.quiet != true)
            return Result(Cause.MAYBE_QUIET_PLACE, Confidence.LOW, Facts(name = zone.name),
                listOfNotNull(Action.CHECK_HERE.takeIf { here && !notHere }, Action.OPEN_MAP))
        if (t.lastUsbAt > after)
            return Result(Cause.AFTER_CABLE, Confidence.LOW, Facts(at = t.lastUsbAt), listOf(Action.HOW_TO_RESTART))
        // 22
        c.clockSkewMs?.takeIf { abs(it) > CLOCK_SKEW_MS }?.let {
            return Result(Cause.PHONE_CLOCK, Confidence.MEDIUM, Facts(count = (abs(it) / 60_000L).toInt()), listOf(Action.DATE_SETTINGS))
        }
        return Result(Cause.UNKNOWN, Confidence.NONE, Facts(at = p?.receivedAt), listOfNotNull(Action.CHECK_HERE.takeIf { here }))
    }

    /** The cause is shown now: NOTE and CALM as soon as late, WARN once the tag is SILENT. */
    fun shown(d: Result?, s: TagStatus): Boolean = d != null && (d.level != Level.WARN || s.kind == StatusKind.SILENT)

    /** The row's band: a WARN or CRIT status, unless a shown NOTE or CALM cause explains a silence. */
    fun band(d: Result?, s: TagStatus): Boolean {
        if (s.level == TagStatus.Level.CRIT) return true
        if (s.level != TagStatus.Level.WARN) return false
        if (s.kind == StatusKind.SILENT && d != null && d.level != Level.WARN) return false
        return true
    }

    /**
     * The tag restarts again and again: two console windows in an hour, two boots in 6 h, or a
     * boot report with two resets in a row. Also while it still reports (the restart notice).
     */
    fun restarting(t: Tracker, now: Long): Result? {
        val wins = t.bleBoots.filter { now - it in 0..RESTART_WINDOW_MS }
        if (wins.size >= 2) return Result(Cause.RESTARTING, Confidence.MEDIUM, Facts(times = wins, at = wins.first()),
            listOf(Action.OPEN_BLE, Action.SERVICE))
        val boots = t.bootRecs.filter { now - it.at in 0..BOOTS_WINDOW_MS }
        val reported = boots.lastOrNull { !it.inferred && (it.resets ?: 0) >= 2 }
        if (reported != null) return Result(Cause.RESTARTING, Confidence.HIGH,
            Facts(times = boots.map { it.at }, at = boots.first().at, count = reported.resets, word = reported.cause), listOf(Action.OPEN_BLE, Action.SERVICE))
        if (boots.size >= 2) return Result(Cause.RESTARTING, if (boots.any { !it.inferred }) Confidence.HIGH else Confidence.MEDIUM,
            Facts(times = boots.map { it.at }, at = boots.first().at), listOf(Action.OPEN_BLE, Action.SERVICE))
        return null
    }

    /** The first boot of the current restart episode (ms), for one notice per episode; null = none. */
    fun restartEpisode(t: Tracker, now: Long): Long? {
        val h = t.health
        if (h != null && h.fresh(now) && (h.resets ?: 0) >= 2 && h.faultReset) return h.at - (h.resets ?: 0) * 60_000L
        return restarting(t, now)?.facts?.times?.minOrNull()
    }

    private fun batteryEmpty(t: Tracker, now: Long): Result? {
        val p = t.last ?: return null
        val b = p.battery
        if (b != null && b <= EMPTY_PCT) return Result(Cause.BATTERY_EMPTY, Confidence.HIGH, Facts(at = p.receivedAt, count = b), listOf(Action.OPEN_MAP))
        if (t.critBattNotified) return Result(Cause.BATTERY_EMPTY, Confidence.HIGH, Facts(at = p.receivedAt, count = b), listOf(Action.OPEN_MAP))
        if (p.lowBatt && b != null) {
            val est = BatteryModel.estimate(t.positions.mapNotNull { q -> q.battery?.let { BatterySample(q.receivedAt, it, q.mode) } })
            if (est != null && !est.capped && est.ratePctPerDay > 0) {
                val days = (now - p.receivedAt).toDouble() / BatteryModel.DAY_MS
                if (b - est.ratePctPerDay * days <= STOPS_PCT)
                    return Result(Cause.BATTERY_EMPTY, Confidence.MEDIUM, Facts(at = p.receivedAt, count = b), listOf(Action.OPEN_MAP))
            }
        }
        return null
    }

    /** a SET_IDLE on its way (or just confirmed) with a longer period: the next report may come on either schedule */
    private fun schedulePending(t: Tracker, now: Long): Result? {
        val p = t.last ?: return null
        val oldS = t.reportPeriodMin * 60
        val q = t.pending
        if (q != null && q.kind == "INTERVAL") {
            val c = app.libretag.proto.LibreTag.hexToBytes(q.body)?.let { app.libretag.proto.Command.decode(it) }
            val newMin = c?.let { cmd -> app.libretag.proto.Command.idleIntervalMin(cmd)?.let { it * (app.libretag.proto.Command.idleReportEvery(cmd) ?: 1) } }
            if (newMin != null && newMin * 60 > oldS) {
                val by = p.receivedAt + newMin * 60_000L + Status.SILENT_MARGIN_MS
                if (now < by) return Result(Cause.SCHEDULE_PENDING, Confidence.MEDIUM, Facts(periodS = newMin * 60, deadline = by))
            }
        }
        return null
    }

    private fun scheduleMismatch(t: Tracker, now: Long): Result? {
        val h = t.health
        val appMin = t.reportPeriodMin
        if (h != null && h.fresh(now) && h.intervalMin != null) {
            val tagMin = h.intervalMin * (h.reportEvery ?: 1).coerceAtLeast(1)
            if (tagMin != appMin) return Result(Cause.SCHEDULE_MISMATCH, Confidence.HIGH,
                Facts(periodS = tagMin * 60, period2S = appMin * 60, count = h.intervalMin), listOf(Action.USE_TAG_SCHEDULE, Action.SEND_MINE))
        }
        val p = t.positions.lastOrNull { it.via != "usb-quiet" && it.mode == "IDLE" && !it.quiet && it.nextInS != null }
        if (p != null && p == t.last && Status.modeNow(t, now) == "IDLE") {
            val tagS = p.nextInS!!
            if (abs(tagS - appMin * 60L) > appMin * 60L * 0.15 && tagS < 3600L * 34)
                return Result(Cause.SCHEDULE_MISMATCH, Confidence.MEDIUM,
                    Facts(periodS = tagS.toInt(), period2S = appMin * 60), listOf(Action.SEND_MINE))
        }
        if (t.cableDirty) return Result(Cause.SCHEDULE_MISMATCH, Confidence.MEDIUM, Facts(period2S = appMin * 60), listOf(Action.SEND_MINE))
        return null
    }

    /** the last two reports heard no repeater (or only a weak one) and came only through Nostr */
    private fun outOfRange(t: Tracker): Boolean {
        val r = t.positions.filter { it.via != "usb-quiet" && it.via != "usb" }.takeLast(2)
        if (r.size < 2) return false
        return r.all { p -> (p.nbTotal == 0 || (p.neighbours.maxOfOrNull { it.snrDb } ?: -99.0) < -10.0) && p.via != "mesh" }
    }

    /** the own node was up in the last 7 days, is down now, and this tag's last 5 reports all came by it */
    fun nodeDown(t: Tracker, c: DiagContext, now: Long): Boolean {
        val up = c.meshUpAt ?: return false
        if (c.mesh != MeshLink.DOWN || now - up > 7 * 86_400_000L) return false
        val r = t.positions.filter { it.via != "usb-quiet" && it.via != "usb" }.takeLast(5)
        return r.size == 5 && r.all { it.via == "mesh" }
    }

    /** "Check if it's here" can be offered: a tag of ours with keys, Bluetooth on, nothing asked right now */
    fun checkHereOffered(t: Tracker, c: DiagContext, now: Long): Boolean =
        c.bluetooth && t.canCommand && !t.isShared && t.keyHex.isNotEmpty() && t.pending?.kind != "BLE_CONSOLE"

    /** the zone the last fix lies in, or within [ZONE_EDGE_M] of its edge */
    fun zoneNear(t: Tracker, p: Position): Zone? {
        val (lat, lon) = when {
            p.hasFix -> p.lat to p.lon
            p.lastFixTime > 0 -> p.lastLat to p.lastLon
            else -> return null
        }
        return t.zones.firstOrNull { Geo.distanceM(lat, lon, it.lat, it.lon) <= it.radiusM + ZONE_EDGE_M }
    }

    /** the tag's next wake-up after [now] on its schedule, an estimate (ms) */
    fun nextWake(t: Tracker, now: Long): Long? {
        val next = Status.nextAt(t, now) ?: return null
        val per = t.intervalMin * 60_000L
        if (per <= 0 || next > now) return next
        return next + ((now - next) / per + 1) * per
    }

    // ---------------------------------------------------------------- findings and checks

    enum class Finding { NODE_DOWN, BATTERY_CLIFF, NO_GPS_RUN, COUNTER_BACK, COUNTER_AHEAD, FIRMWARE_BEHIND, FLOOD_CAP, RESTARTING }

    data class Found(val finding: Finding, val facts: Facts = Facts())

    /** Things worth a hint even while the tag reports normally (5, second table). */
    fun findings(t: Tracker, c: DiagContext, now: Long, bundledFirmware: String? = null): List<Found> {
        val out = mutableListOf<Found>()
        if (nodeDown(t, c, now)) out += Found(Finding.NODE_DOWN, Facts(at = c.meshUpAt))
        val r = t.positions.filter { it.via != "usb-quiet" && it.n >= 0 }.sortedBy { it.n }
        if (r.size >= 2) {
            val a = r[r.size - 2]; val b = r.last()
            val ba = a.battery; val bb = b.battery
            if (ba != null && bb != null && ba - bb >= 25 && !b.boot &&
                b.receivedAt - a.receivedAt < 3 * CounterGaps.periodMs(t, a)) out += Found(Finding.BATTERY_CLIFF, Facts(count = ba - bb, at = b.receivedAt))
        }
        val noFix = r.takeLastWhile { !it.hasFix }
        if (noFix.size >= 3 && r.size > noFix.size) out += Found(Finding.NO_GPS_RUN, Facts(at = noFix.first().receivedAt, count = noFix.size))
        t.counterBackAt?.let { if (now - it < 7 * 86_400_000L) out += Found(Finding.COUNTER_BACK, Facts(at = it)) }
        val hn = t.health?.n
        if (hn != null && t.lastN >= 0 && hn - t.lastN > COUNTER_WINDOW) out += Found(Finding.COUNTER_AHEAD)
        val fw = t.firmware
        if (fw != null && bundledFirmware != null && app.libretag.usb.ConsoleParsers.isOlder(fw, bundledFirmware))
            out += Found(Finding.FIRMWARE_BEHIND, Facts(version = fw, word = bundledFirmware))
        val p = t.last
        val ns = p?.nextInS
        if (p != null && ns != null && (p.mode == "TRACK" || p.mode == "LOST") && ns >= 3600 &&
            (t.modeIntervalS ?: if (p.mode == "LOST") Status.LOST_INTERVAL_S else Status.TRACK_INTERVAL_S) < 3600)
            out += Found(Finding.FLOOD_CAP, Facts(periodS = ns.toInt()))
        restarting(t, now)?.let { out += Found(Finding.RESTARTING, it.facts) }
        return out
    }

    enum class CheckState { OK, WARN, UNKNOWN }

    enum class Check { ONLINE, RELAYS, NODE, BRIDGES, LAST_REPORT, LOST, BATTERY, BLUETOOTH, CABLE, RESTARTS, SCHEDULE, FIRMWARE, CLOCK }

    data class CheckRow(val check: Check, val state: CheckState, val facts: Facts = Facts())

    /** "All checks": one row per input, in the order of the design (7.2). */
    fun checklist(t: Tracker, c: DiagContext, now: Long, bundledFirmware: String? = null): List<CheckRow> {
        val out = mutableListOf<CheckRow>()
        val p = t.last
        out += CheckRow(Check.ONLINE, if (c.online) CheckState.OK else CheckState.WARN)
        out += CheckRow(Check.RELAYS, when {
            c.relaysAll == 0 -> CheckState.UNKNOWN
            c.relaysUp == 0 -> CheckState.WARN
            else -> CheckState.OK
        }, Facts(count = c.relaysUp, at2 = c.relaysAll.toLong()))
        out += CheckRow(Check.NODE, when (c.mesh) {
            MeshLink.UP -> CheckState.OK
            MeshLink.DOWN -> if (nodeDown(t, c, now)) CheckState.WARN else CheckState.UNKNOWN
            MeshLink.NEVER -> CheckState.UNKNOWN
        }, Facts(word = c.mesh.name, at = c.meshUpAt))
        out += CheckRow(Check.BRIDGES, if (c.receipt != null) CheckState.OK else CheckState.UNKNOWN,
            Facts(at = c.receipt?.atMs, word = c.receipt?.hops))
        out += CheckRow(Check.LAST_REPORT, if (p == null) CheckState.UNKNOWN else if (late(t, now)) CheckState.WARN else CheckState.OK,
            Facts(at = p?.receivedAt, word = p?.via, count = p?.n?.takeIf { it >= 0 }?.toInt()))
        val (lost, of) = CounterGaps.lossCount(t, now)
        out += CheckRow(Check.LOST, if (of == 0) CheckState.UNKNOWN else if (lost * 4 >= of) CheckState.WARN else CheckState.OK,
            Facts(count = lost, at2 = of.toLong()))
        out += CheckRow(Check.BATTERY, when {
            p?.battery == null -> CheckState.UNKNOWN
            p.battery <= Status.LOW_BATTERY_PCT || p.lowBatt -> CheckState.WARN
            else -> CheckState.OK
        }, Facts(count = p?.battery, at = p?.receivedAt))
        val s = t.bleSight
        out += CheckRow(Check.BLUETOOTH, if (s != null) CheckState.OK else CheckState.UNKNOWN, Facts(at = s?.at, word = s?.kind))
        out += CheckRow(Check.CABLE, when (c.cable) {
            Cable.TAG -> CheckState.OK
            Cable.BOOTLOADER, Cable.ESP_DOWNLOAD -> CheckState.WARN
            Cable.NONE -> CheckState.UNKNOWN
        }, Facts(word = c.cable.name, at = t.lastUsbAt.takeIf { it > 0 }))
        val r = restarting(t, now)
        val boots = (t.bootRecs.map { it.at } + t.bleBoots).sorted()
        out += CheckRow(Check.RESTARTS, if (r != null) CheckState.WARN else if (boots.isEmpty()) CheckState.UNKNOWN else CheckState.OK,
            Facts(at = boots.lastOrNull(), count = boots.size))
        val mism = scheduleMismatch(t, now)
        out += CheckRow(Check.SCHEDULE, if (mism != null) CheckState.WARN else CheckState.OK,
            Facts(periodS = t.reportPeriodMin * 60, period2S = t.intervalMin * 60, at = t.settingsReadAt.takeIf { it > 0 }))
        val fw = t.firmware
        out += CheckRow(Check.FIRMWARE, when {
            fw == null -> CheckState.UNKNOWN
            app.libretag.usb.ConsoleParsers.isOlder(fw, c.minFirmware) -> CheckState.WARN
            bundledFirmware != null && app.libretag.usb.ConsoleParsers.isOlder(fw, bundledFirmware) -> CheckState.UNKNOWN
            else -> CheckState.OK
        }, Facts(version = fw, word = bundledFirmware))
        out += CheckRow(Check.CLOCK, when {
            c.clockSkewMs == null -> CheckState.UNKNOWN
            abs(c.clockSkewMs) > CLOCK_SKEW_MS -> CheckState.WARN
            else -> CheckState.OK
        }, Facts(count = c.clockSkewMs?.let { (abs(it) / 60_000L).toInt() }))
        return out
    }
}

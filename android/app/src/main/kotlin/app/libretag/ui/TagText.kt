package app.libretag.ui

import app.libretag.data.WifiZoneTransit
import android.content.Context
import app.libretag.CommandKind
import app.libretag.LibreTagApp
import app.libretag.R
import app.libretag.Reach
import app.libretag.data.Position
import app.libretag.proto.Command
import app.libretag.proto.LibreTag
import app.libretag.data.Status
import app.libretag.data.StatusLines
import app.libretag.data.TagStatus
import app.libretag.data.Tracker

/** The brief's sentences for a tag: status, position and certainty, command slips (app-brief.md §3). */
object TagText {
    fun typeName(ctx: Context, type: String) = ctx.getString(when (type) {
        "person" -> R.string.type_person
        "vehicle" -> R.string.type_vehicle
        else -> R.string.type_thing
    })

    fun cmdName(ctx: Context, k: CommandKind) = ctx.getString(when (k) {
        CommandKind.LOST -> R.string.cmd_lost
        CommandKind.TRACK -> R.string.cmd_track
        CommandKind.BEEP -> R.string.cmd_beep
        CommandKind.NORMAL -> R.string.cmd_normal
        CommandKind.BLE_SCAN -> R.string.cmd_ble_scan
        CommandKind.INTERVAL -> R.string.cmd_interval
        CommandKind.BLE_CONSOLE -> R.string.cmd_ble_console
    })

    /** A waiting command with its argument: "Track 1 h, every 5 min", "Reports every 2 h". */
    fun cmdLabel(ctx: Context, p: app.libretag.data.PendingCommand): String? {
        val k = CommandKind.of(p.kind) ?: return null
        val c = LibreTag.hexToBytes(p.body)?.let { Command.decode(it) }
        return when (k) {
            CommandKind.TRACK -> c?.let { Command.modeIntervalS(it) }?.let {
                ctx.getString(R.string.cmd_track_for, Format.duration(ctx, (Command.modeDurationMin(c) ?: CommandKind.TRACK_MIN) * 60_000L), Format.every(ctx, it))
            }
            CommandKind.INTERVAL -> c?.let { cmd -> Command.idleIntervalMin(cmd)?.let { it * (Command.idleReportEvery(cmd) ?: 1) } }
                ?.let { ctx.getString(R.string.cmd_interval_to, Format.every(ctx, it * 60)) }
            else -> null
        } ?: cmdName(ctx, k)
    }

    fun zoneOrHome(ctx: Context, name: String?) = zoneLabel(ctx, name)

    /** A zone's name for a sentence: Home when unknown, "a Wi-Fi zone" for one the app can't name. */
    fun zoneLabel(ctx: Context, name: String?): String = when (name) {
        null -> ctx.getString(R.string.zone_home)
        WifiZoneTransit.UNNAMED -> ctx.getString(R.string.zone_wifi_unnamed)
        else -> name
    }

    private fun unnamedWifi(name: String?) = name == WifiZoneTransit.UNNAMED

    /** A time ahead: "~14:31" (an estimate) or "14:31" for a sentence that says "at the latest". */
    private fun ahead(ctx: Context, a: StatusLines.Ahead, now: Long) =
        (if (a.deadline) "" else "~") + Format.clock(ctx, a.at, now)

    /**
     * The one status sentence (ui-review §3). [compact]: a list row, where "heard" is the short
     * form. The detail passes the status without low battery (Status.of(lowBattery = false)).
     */
    fun status(ctx: Context, t: Tracker, s: TagStatus, now: Long = System.currentTimeMillis(), compact: Boolean = false): String {
        val l = StatusLines.of(t, s, now)
        return when (l.plate) {
            StatusLines.Plate.WAITING -> ctx.getString(R.string.st_waiting)
            StatusLines.Plate.HEARD -> when {
                s.heardAt == null -> ctx.getString(R.string.st_waiting)
                compact -> Format.heardRow(ctx, s.heardAt, now)
                s.approx -> ctx.getString(R.string.st_heard, "~" + Format.at(ctx, s.heardAt, now))
                else -> ctx.getString(R.string.st_heard, Format.agoLong(ctx, s.heardAt, now))
            }
            StatusLines.Plate.MISSED -> ctx.resources.getQuantityString(R.plurals.st_missed, s.missed, s.missed,
                "~" + Format.clock(ctx, s.nextAt ?: now, now))
            StatusLines.Plate.HEARD_LATE -> ctx.getString(R.string.st_heard_late, Format.agoLong(ctx, s.heardAt ?: now, now))
            StatusLines.Plate.LEFT -> {
                val at = s.leftAt?.let { Format.at(ctx, it, now) } ?: ""
                if (unnamedWifi(l.place)) ctx.getString(R.string.st_left_wifi, at)
                else ctx.getString(R.string.st_left, zoneLabel(ctx, l.place), at)
            }
            StatusLines.Plate.QUIET_PLACE -> if (unnamedWifi(l.place)) ctx.getString(R.string.st_quiet_wifi_place)
                else ctx.getString(R.string.st_quiet_place, zoneLabel(ctx, l.place))
            StatusLines.Plate.QUIET_WITH_YOU -> ctx.getString(R.string.st_quiet_with_you)
            StatusLines.Plate.QUIET_STILL -> ctx.getString(R.string.st_quiet_still)
            StatusLines.Plate.QUIET_PARKED -> ctx.getString(R.string.st_quiet_parked2)
            StatusLines.Plate.TRACKING -> {
                val every = Format.every(ctx, s.intervalS ?: Status.TRACK_INTERVAL_S)
                l.plateAhead?.let { ctx.getString(R.string.st_tracking_until, every, Format.clock(ctx, it, now)) }
                    ?: ctx.getString(R.string.st_tracking, every)
            }
            StatusLines.Plate.LOST -> ctx.getString(R.string.st_lost2, Format.every(ctx, s.intervalS ?: Status.LOST_INTERVAL_S))
            StatusLines.Plate.GUARD_ALARM -> ctx.getString(R.string.st_alarm_guard)
            StatusLines.Plate.NOT_HEARD -> ctx.getString(R.string.st_not_heard, Format.duration(ctx, s.silentForMs),
                Format.every(ctx, s.intervalS ?: t.intervalMin * 60))
            StatusLines.Plate.LOW_BATTERY -> s.battery?.let { ctx.getString(R.string.st_low_battery, it) }
                ?: ctx.getString(R.string.st_low_battery_unknown)
        }
    }

    /** The second line under the big status in the detail; null hides it. */
    fun statusSub(ctx: Context, t: Tracker, s: TagStatus, now: Long = System.currentTimeMillis()): String? {
        val sub = StatusLines.of(t, s, now).sub
        fun past() = (if (sub.approx) "~" else "") + Format.at(ctx, sub.past ?: now, now)
        return when (sub.kind) {
            StatusLines.SubKind.PAIRED -> ctx.getString(R.string.sub_paired, past())
            StatusLines.SubKind.SINCE -> ctx.getString(R.string.sub_since, past())
            StatusLines.SubKind.SINCE_DEVICE -> t.knownDevices.singleOrNull()?.name?.takeIf { it.isNotBlank() }
                ?.let { ctx.getString(R.string.sub_since_device, past(), it) }
                ?: ctx.getString(R.string.sub_since_your_device, past())
            StatusLines.SubKind.LOST_UNTIL -> ctx.getString(R.string.sub_lost_until2, Format.clock(ctx, sub.ahead!!, now))
            StatusLines.SubKind.GUARD_LEFT -> sub.past?.let { ctx.getString(R.string.sub_guard_left, Format.at(ctx, it, now)) }
            StatusLines.SubKind.NOT_HEARD -> {
                val iv = (s.intervalS ?: (t.reportPeriodMin * 60)).coerceAtLeast(60)
                val missed = ((s.silentForMs / 1000) / iv).toInt()
                ctx.getString(R.string.sub_not_heard, past(), ctx.resources.getQuantityString(R.plurals.reports_missed, missed, missed))
            }
            // a shared tag says whose it is where nothing else needs the line
            StatusLines.SubKind.NONE -> if (t.isShared) ctx.getString(when { t.sharedAdmin -> R.string.sub_shared_admin; t.sharedControl -> R.string.sub_shared_control; else -> R.string.sub_shared_view }, t.sharedBy) else null
        }
    }

    /** The When row's one "next report" line; null when there is nothing to say. */
    fun nextLine(ctx: Context, t: Tracker, s: TagStatus, now: Long = System.currentTimeMillis()): String? {
        val l = StatusLines.of(t, s, now)
        val n = l.next
        val trigger = when (n.kind) {
            StatusLines.NextKind.WHEN_LEAVES -> if (unnamedWifi(l.place)) ctx.getString(R.string.next_when_leaves_wifi)
                else ctx.getString(R.string.next_when_leaves, zoneLabel(ctx, l.place))
            StatusLines.NextKind.WHEN_PART -> ctx.getString(R.string.next_when_part)
            StatusLines.NextKind.WHEN_MOVES -> ctx.getString(R.string.next_when_moves)
            else -> null
        }
        if (trigger != null) {
            val w = n.withinS?.let { ctx.getString(R.string.next_within, trigger, Format.duration(ctx, it * 1000L)) } ?: trigger
            return n.ahead?.let { ctx.getString(R.string.next_and_latest, w, Format.clock(ctx, it.at, now)) } ?: w
        }
        return when (n.kind) {
            StatusLines.NextKind.FIRST_WITHIN -> ctx.getString(R.string.next_first, Format.duration(ctx, (n.intervalS ?: 3600) * 1000L))
            StatusLines.NextKind.REPORT -> ctx.getString(R.string.next_report, Format.clock(ctx, n.ahead!!.at, now))
            StatusLines.NextKind.DUE -> ctx.getString(R.string.next_due, Format.clock(ctx, n.ahead!!.at, now))
            StatusLines.NextKind.LATEST -> ctx.getString(R.string.next_latest, Format.clock(ctx, n.ahead!!.at, now))
            StatusLines.NextKind.OVERDUE -> ctx.getString(R.string.next_overdue)
            else -> null
        }
    }

    /**
     * The one line under the commands: when a command reaches the tag ("A command reaches it
     * ~1:13"), with " · through a bridge" when only Nostr reaches it, or the offline sentence.
     */
    fun reachLine(ctx: Context, t: Tracker, s: TagStatus, now: Long = System.currentTimeMillis(), reach: Reach = LibreTagApp.instance.reach.value): String {
        if (reach == Reach.OFFLINE) return ctx.getString(R.string.cmd_offline)
        val r = StatusLines.of(t, s, now).reach
        val line = when (r.kind) {
            StatusLines.ReachKind.AT -> ctx.getString(R.string.cmd_reach, Format.clock(ctx, r.ahead!!.at, now))
            StatusLines.ReachKind.WITH_REPORT -> ctx.getString(R.string.cmd_reach_report, Format.clock(ctx, r.ahead!!.at, now))
            StatusLines.ReachKind.WHEN_HEARD -> ctx.getString(R.string.cmd_reach_heard)
            StatusLines.ReachKind.FIRST -> ctx.getString(R.string.cmd_reach_first)
        }
        return if (reach == Reach.NOSTR) ctx.getString(R.string.cmd_reach_bridge, line) else line
    }

    /** A place a quiet "zone" report stands for; [name] null = one the app can't name (shown as Home). */
    data class Place(val name: String?)

    /** Where the report says the tag rests (quiet at one of its places), else null. */
    fun placeOf(t: Tracker, p: Position): Place? =
        if (p.quiet && p.quietReason == "zone") Place(Status.quietZoneName(t, p)) else null

    /** "At Home", "At a Wi-Fi place" */
    fun atPlace(ctx: Context, place: Place): String =
        if (unnamedWifi(place.name)) ctx.getString(R.string.where_at_wifi_place) else ctx.getString(R.string.where_at_place, zoneLabel(ctx, place.name))

    /** Position and certainty for a report ("GPS ±8 m · 48.70120, 21.25410", "No GPS · area ±1.2 km (68 %)", "At Home"). */
    fun position(ctx: Context, t: Tracker, p: Position?, latest: Boolean = true, now: Long = System.currentTimeMillis()): String {
        if (p == null) return ctx.getString(R.string.pos_none)
        // quiet at one of its places: that is where it is
        placeOf(t, p)?.let { place ->
            if (p.hasFix && !unnamedWifi(place.name)) p.haccM?.let {
                return ctx.getString(R.string.pos_at_place_gps, zoneLabel(ctx, place.name), Format.distance(ctx, it.toDouble()))
            }
            return atPlace(ctx, place)
        }
        if (p.hasFix) {
            val fixMs = if (p.fixTime > 0) p.fixTime * 1000 else p.receivedAt
            if (latest && now - p.receivedAt > 90 * 60_000L)
                return ctx.getString(R.string.pos_last_gps, Format.agoAt(ctx, fixMs, now), Format.coord(p.lat, p.lon))
            return p.haccM?.let { ctx.getString(R.string.pos_gps, Format.distance(ctx, it.toDouble()), Format.coord(p.lat, p.lon)) }
                ?: ctx.getString(R.string.pos_gps_noacc, Format.coord(p.lat, p.lon))
        }
        val e = p.est
        val wifi = t.wifiFixes[p.n]
        return when {
            wifi != null -> ctx.getString(R.string.pos_wifi_area, Format.distance(ctx, wifi.r68))
            e != null && e.state == "ok" -> ctx.getString(R.string.pos_area, Format.distance(ctx, e.r68))
            p.wifi != null -> wifiHeard(ctx, p)
            e != null && e.state == "ambiguous" -> ctx.getString(R.string.pos_ambiguous)
            e != null && e.state == "nodb" -> ctx.getString(R.string.pos_need_map)
            e == null && p.neighbours.isNotEmpty() -> ctx.getString(R.string.pos_estimating)
            p.lastFixTime > 0 -> ctx.getString(R.string.pos_no_gps_last, Format.agoAt(ctx, p.lastFixTime * 1000, now))
            p.nbTotal == 0 -> ctx.getString(R.string.pos_no_repeater)
            else -> ctx.getString(R.string.pos_no_gps)
        }
    }

    /** "No GPS · 6 Wi-Fi networks heard" */
    fun wifiHeard(ctx: Context, p: Position): String {
        val w = p.wifi ?: return ctx.getString(R.string.where_no_gps)
        val count = w.apCount.takeIf { it > 0 } ?: (1 + w.tails.size + w.others.size)
        return ctx.resources.getQuantityString(R.plurals.where_wifi_heard, count, count)
    }

    /** Where to point a map app: the fix, the area centre, or the last fix. */
    fun target(t: Tracker, p: Position?): Triple<Double, Double, Double?>? {
        if (p == null) return null
        if (p.hasFix) return Triple(p.lat, p.lon, null)
        t.wifiFixes[p.n]?.let { return Triple(it.lat, it.lon, it.r68) }
        p.est?.takeIf { it.state == "ok" }?.let { return Triple(it.lat, it.lon, it.r68) }
        if (p.lastFixTime > 0) return Triple(p.lastLat, p.lastLon, null)
        val prev = t.positions.lastOrNull { it.hasFix && it.n < p.n }
        return prev?.let { Triple(it.lat, it.lon, null) }
    }

    enum class SlipState { WAITING, SENT, CONFIRMED, FAILED }

    data class Slip(val state: SlipState, val text: String)

    /**
     * The slip: waiting, sent, confirmed or no answer. In the detail it replaces the reach line;
     * [compact] (a list row) gives the command and a time only: "Beep · ~1:13".
     */
    fun slip(ctx: Context, t: Tracker, s: TagStatus, now: Long = System.currentTimeMillis(), compact: Boolean = false): Slip? {
        val p = t.pending
        if (p != null) {
            CommandKind.of(p.kind) ?: return null
            val c = cmdLabel(ctx, p) ?: return null
            val reach = StatusLines.of(t, s, now).reach
            val at = reach.ahead
            // sent at a check that passed without an answer: it waits for the next check
            val sentStale = p.sentForCheck != null && p.sentAt != null && now - p.sentAt > CHECK_SENT_KEEP_MS
            // R20: a bridge said it could not send the last slot
            val missed = p.missAt != null && p.missAt > (p.sentAt ?: 0L) && now - p.missAt < CHECK_SENT_KEEP_MS * 10
            val state = when {
                p.notConfirmed || missed -> SlipState.FAILED
                p.sentAt != null && !sentStale -> SlipState.SENT
                else -> SlipState.WAITING
            }
            if (compact) {
                val time = if (state == SlipState.SENT) Format.at(ctx, p.sentAt!!, now) else at?.let { ahead(ctx, it, now) }
                return Slip(state, time?.let { ctx.getString(R.string.slip2_row, c, it) } ?: c)
            }
            return Slip(state, when (state) {
                SlipState.FAILED -> if (missed) ctx.getString(R.string.slip2_not_sent, c, Format.clock(ctx, p.missAt!!, now))
                    else at?.let { ctx.getString(R.string.slip2_retry, c, Format.clock(ctx, it.at, now)) }
                    ?: ctx.getString(R.string.slip2_retry_later, c)
                SlipState.SENT -> ctx.getString(R.string.slip2_sent, c, Format.at(ctx, p.sentAt!!, now))
                else -> when {
                    reach.kind == StatusLines.ReachKind.FIRST -> ctx.getString(R.string.slip2_out_first, c)
                    // 2.6: no grid the app is sure of: no time, it still goes out on the grids it has
                    Status.scheduleUnknown(t, now) -> ctx.getString(R.string.slip2_schedule_unknown, t.name)
                    p.mailbox && p.accepted == 0 && now - p.queuedAt > NO_RELAY_AFTER_MS -> ctx.getString(R.string.slip2_no_relay, c)
                    at == null -> ctx.getString(R.string.slip2_out_heard, c)
                    at.deadline -> ctx.getString(R.string.slip2_out_latest, c, Format.clock(ctx, at.at, now))
                    else -> ctx.getString(R.string.slip2_next_try, c, Format.clock(ctx, at.at, now))
                }
            })
        }
        val l = t.lastCommand ?: return null
        // a confirmed command stays on the slip for a while, then it's history
        if (now - l.confirmedAt > SLIP_KEEP_MS) return null
        val k = CommandKind.of(l.kind) ?: return null
        val at = Format.at(ctx, l.confirmedAt, now)
        return Slip(SlipState.CONFIRMED, if (compact) ctx.getString(R.string.slip2_row, cmdName(ctx, k), at)
            else ctx.getString(R.string.slip2_confirmed, cmdName(ctx, k), at))
    }

    /**
     * The link line of the detail (A4): "In range of your node", "In range of a bridge", over one or
     * two repeaters, or out of range; null before a quiet mesh report.
     */
    fun link(ctx: Context, t: Tracker): String? = when (app.libretag.data.QuietPlan.link(t)) {
        app.libretag.data.QuietPlan.Link.NEAR_NODE -> ctx.getString(R.string.link_near_node)
        app.libretag.data.QuietPlan.Link.NEAR_BRIDGE -> ctx.getString(R.string.link_near_bridge)
        app.libretag.data.QuietPlan.Link.ROUTED -> ctx.getString(R.string.link_routed)
        app.libretag.data.QuietPlan.Link.FAR -> ctx.getString(R.string.link_far)
        app.libretag.data.QuietPlan.Link.UNKNOWN -> null
    }

    /** Route of a report: "via mesh, 1 hop", "via Nostr (bridge)", "from the tag's memory (USB)". */
    fun route(ctx: Context, p: Position): String = when (p.via) {
        "nostr" -> ctx.getString(R.string.route_nostr)
        "usb" -> ctx.getString(R.string.route_usb)
        "usb-quiet" -> ctx.getString(R.string.route_usb_quiet)
        else -> p.hops?.let { ctx.resources.getQuantityString(R.plurals.route_mesh_hops, it, it) } ?: ctx.getString(R.string.route_mesh)
    }

    /** One sentence for TalkBack on a row (status, position, age and battery in words). */
    fun aria(ctx: Context, t: Tracker, s: TagStatus, now: Long = System.currentTimeMillis()): String {
        val d = runCatching { LibreTagApp.instance.diag.value[t.id] }.getOrNull()
        val parts = mutableListOf(t.name + ", " + typeName(ctx, t.type), DiagText.status(ctx, t, s, d, now, compact = false), position(ctx, t, t.last, true, now))
        if (t.guarding && !t.guardAlarm) parts += ctx.getString(R.string.a11y_guarded)
        s.heardAt?.let { parts += ctx.getString(R.string.a11y_heard, Format.ageWords(ctx, it, now)) }
        s.battery?.let { parts += ctx.getString(R.string.a11y_battery, it) }
        slip(ctx, t, s, now)?.let { parts += it.text }
        return parts.joinToString(". ").replace(" · ", ", ")
    }

    const val SLIP_KEEP_MS = 6 * 3600_000L
    /** a command sent at a check shows "sent" this long; without an answer it waits for the next check */
    const val CHECK_SENT_KEEP_MS = 3 * 60_000L
    /** "no relay took it yet" only after the first publish had time */
    const val NO_RELAY_AFTER_MS = 20_000L
}

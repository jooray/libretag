package app.libretag.data

/**
 * The one primary status of a tag, in priority order (docs/design/app-brief.md §3):
 * Lost > Alarm > Silent > Low battery > Tracking > Quiet > Left > OK. Pure, so it is unit tested and
 * shared by the list, the detail, notifications and the support ask. ALARM is only a guard alarm
 * (0.9): leaving a place is the neutral LEFT state, an event shown for a while.
 */
enum class StatusKind {
    LOST, ALARM, SILENT, LOW_BATTERY, TRACKING, QUIET,
    /** 0.9: left a place, not at one again, under 12 h ([Tracker.leftRecently]); level NONE */
    LEFT,
    OK, WAITING,
}

data class TagStatus(
    val kind: StatusKind,
    /** when it was last heard (ms), null if never */
    val heardAt: Long?,
    /** when the next report is expected (ms), null if unknown (silent, never heard) */
    val nextAt: Long?,
    /** the alarm, for ALARM: "guard:<ms>" */
    val alarm: String? = null,
    /** until when TRACK / LOST runs (ms) */
    val until: Long? = null,
    val intervalS: Int? = null,
    val battery: Int? = null,
    /** zone name for QUIET in a zone; "owner" when quiet near the owner's devices */
    val quietWhere: String? = null,
    /** SILENT: how long it has been overdue (ms) and the expected interval (s) */
    val silentForMs: Long = 0,
    /** QUIET with checks (0.7): the next check (ms) and how often it checks (s) */
    val nextCheckAt: Long? = null,
    val checkEveryS: Int? = null,
    /**
     * 0.8.1: the last report came only with a time range (a bridge drained it late): [heardAt]
     * is the earliest possible time, and [nextAt] is the latest the next report is due ("by"),
     * not an estimate ("~"). No check times then.
     */
    val approx: Boolean = false,
    /** LEFT: the place it left ([WifiZoneTransit.UNNAMED] for one of several Wi-Fi places) and when (ms) */
    val leftPlace: String? = null,
    val leftAt: Long? = null,
    /**
     * 0.12: expected reports that did not come, still inside the tolerance ([Status.allowedMissed]);
     * [nextAt] is then the projected next one (the plate "1 report missed · next ~15:31").
     */
    val missed: Int = 0,
) {
    /** when a waiting command can reach the tag: the next check, else the next report */
    val commandAt: Long? get() = nextCheckAt ?: nextAt

    val level: Level get() = when (kind) {
        StatusKind.LOST, StatusKind.ALARM -> Level.CRIT
        StatusKind.SILENT, StatusKind.LOW_BATTERY -> Level.WARN
        else -> Level.NONE
    }
    /** needs a look: sorted to the top of the list */
    val priority: Int get() = when (kind) {
        StatusKind.LOST -> 0
        StatusKind.ALARM -> 1
        StatusKind.SILENT -> 2
        StatusKind.LOW_BATTERY -> 3
        else -> 9
    }
    val needsLook: Boolean get() = priority < 9
    /** never show the support ask next to these */
    val blocksSupport: Boolean get() = kind == StatusKind.LOST || kind == StatusKind.ALARM || kind == StatusKind.SILENT

    enum class Level { CRIT, WARN, NONE }
}

object Status {
    const val LOW_BATTERY_PCT = 20
    /** "When a report is 30 min later than expected" */
    const val SILENT_MARGIN_MS = 30 * 60_000L
    /** heartbeat of a quiet tag when the report did not say (SPEC 11 decisions: 24 h) */
    const val HEARTBEAT_MS = 24 * 3600_000L
    const val LOST_INTERVAL_S = 600
    /** Track: one tap = every 5 min; a long press picks one of [TRACK_CHOICES_S] */
    const val TRACK_INTERVAL_S = 300
    val TRACK_CHOICES_S = listOf(120, 300, 600, 900, 1800)
    /** report intervals the settings offer (min) */
    val IDLE_CHOICES_MIN = listOf(15, 30, 60, 120, 180, 360)
    /** 0.7.3: how often the tag wakes up to check for commands between reports (min) */
    val CHECK_EVERY_CHOICES_MIN = listOf(15, 30, 60)

    /** listen for commands at least this often by default (min), also away from a place */
    const val DEFAULT_LISTEN_MIN = 60

    /**
     * (wake-up interval, report_every) after the owner picks a new report period [periodMin]: keep a
     * finer listen interval that still fits, else listen every [DEFAULT_LISTEN_MIN] (or with each
     * report when the period is that short).
     */
    fun splitForPeriod(periodMin: Int, curWakeMin: Int, curEvery: Int): Pair<Int, Int> {
        val keep = curEvery > 1 && curWakeMin in 1 until periodMin && periodMin % curWakeMin == 0
        return wakeSplit(periodMin, if (keep) curWakeMin else DEFAULT_LISTEN_MIN)
    }

    /** (wake-up interval, report_every) for a report period and a check interval (both in min) */
    fun wakeSplit(reportMin: Int, checkMin: Int): Pair<Int, Int> {
        if (checkMin <= 0 || checkMin >= reportMin || reportMin % checkMin != 0) return reportMin to 1
        return checkMin to (reportMin / checkMin).coerceAtMost(24)
    }

    /** How often the tag reports right now (s), from the mode it reported. */
    fun intervalS(t: Tracker, p: Position?, now: Long): Int {
        val mode = p?.mode ?: "IDLE"
        val modeLive = t.modeUntil == null || now < t.modeUntil
        return when {
            mode == "LOST" && modeLive -> t.modeIntervalS ?: LOST_INTERVAL_S
            mode == "TRACK" && modeLive -> t.modeIntervalS ?: TRACK_INTERVAL_S
            else -> t.reportPeriodMin * 60
        }
    }

    /** When the next report is expected (ms): next_in when the report carried it, else the interval. */
    fun nextAt(t: Tracker, now: Long): Long? {
        val p = t.last ?: return null
        // an approximate report was heard at the latest when the bridge drained it
        val at = p.approxUntil ?: p.receivedAt
        p.nextInS?.let { return at + it * 1000 }
        if (p.quiet) return at + HEARTBEAT_MS
        return at + intervalS(t, p, now) * 1000L
    }

    /**
     * [lowBattery] = false: the state under a low battery (the detail's plate keeps "Home · quiet"
     * and its Battery row carries the warning; ui-review §3 rule 6). The list keeps the default.
     */
    fun of(t: Tracker, now: Long = System.currentTimeMillis(), lowBattery: Boolean = true): TagStatus {
        val p = t.last
        val heard = p?.receivedAt
        val next = nextAt(t, now)
        val batt = p?.battery
        // checks run whatever the app shows on top (an old alarm, low battery): a command goes out at the next one
        val plan0 = checkPlan(t)
        val base = TagStatus(StatusKind.OK, heard, next, battery = batt, approx = p?.approx == true,
            nextCheckAt = plan0?.next(now)?.second?.takeIf { next == null || it < next },
            checkEveryS = plan0?.intervalMin?.times(60))
        if (p == null) return base.copy(kind = StatusKind.WAITING)
        val modeLive = t.modeUntil == null || now < t.modeUntil
        if (p.mode == "LOST" && modeLive) {
            return base.copy(kind = StatusKind.LOST, until = t.modeUntil, intervalS = intervalS(t, p, now))
        }
        if (t.guardAlarm) return base.copy(kind = StatusKind.ALARM, alarm = t.alarm)
        val missed = missed(t, now)
        if (missed > allowedMissed(t, now)) {
            return base.copy(kind = StatusKind.SILENT, nextAt = null, silentForMs = now - p.receivedAt,
                intervalS = if (p.quiet) (HEARTBEAT_MS / 1000).toInt() else intervalS(t, p, now))
        }
        if (missed > 0) {
            // one report lost on the way is normal on a shared mesh: the next one is projected
            val proj = next!! + missed * intervalS(t, p, now) * 1000L
            val m = base.copy(missed = missed, nextAt = proj,
                nextCheckAt = base.nextCheckAt?.takeIf { it < proj })
            if (lowBattery && (p.lowBatt || (batt != null && batt <= LOW_BATTERY_PCT))) return m.copy(kind = StatusKind.LOW_BATTERY)
            return m
        }
        if (lowBattery && (p.lowBatt || (batt != null && batt <= LOW_BATTERY_PCT))) return base.copy(kind = StatusKind.LOW_BATTERY)
        if (p.mode == "TRACK" && modeLive) {
            return base.copy(kind = StatusKind.TRACKING, until = t.modeUntil, intervalS = intervalS(t, p, now))
        }
        if (p.quiet) {
            val where = when (p.quietReason) {
                "owner" -> "owner"
                "parked" -> "parked"
                else -> quietZoneName(t, p)
            }
            val plan = checkPlan(t)
            return base.copy(kind = StatusKind.QUIET, quietWhere = where,
                nextCheckAt = plan?.next(now)?.second?.takeIf { next == null || it < next },
                checkEveryS = plan?.intervalMin?.times(60))
        }
        // left a place a while ago and not at one again: an event, shown as a neutral state
        val left = if (t.leftRecently(now)) base.copy(kind = StatusKind.LEFT, leftPlace = t.leftPlace, leftAt = t.leftAt) else base
        // 0.7.3: reporting every n-th wake-up, it listens for commands at the wake-ups in between
        checkPlan(t)?.let { plan ->
            return left.copy(
                nextCheckAt = plan.next(now)?.second?.takeIf { next == null || it < next },
                checkEveryS = plan.intervalMin * 60)
        }
        return left
    }

    /**
     * Expected reports that did not come (docs/design/tag-diagnosis.md 6): 0 while now is within
     * [SILENT_MARGIN_MS] of the expected time, then one more for every period the tag kept since.
     */
    fun missed(t: Tracker, now: Long): Int {
        val p = t.last ?: return 0
        val next = nextAt(t, now) ?: return 0
        if (now <= next + SILENT_MARGIN_MS) return 0
        val per = intervalS(t, p, now) * 1000L
        if (p.quiet || per <= 0) return 1
        return (1 + (now - next - SILENT_MARGIN_MS) / per).coerceAtMost(1000).toInt()
    }

    /**
     * How many missed reports are tolerated before "silent": none for a guarded tag, in LOST or
     * TRACK, or after a QUIET report (a heartbeat); one otherwise; two on a lossy path
     * ([CounterGaps.lossRate] at least 25 %).
     */
    fun allowedMissed(t: Tracker, now: Long): Int {
        val p = t.last ?: return 0
        if (t.guarding || p.quiet) return 0
        val mode = modeNow(t, now)
        if (mode == "LOST" || mode == "TRACK") return 0
        return if (CounterGaps.lossRate(t, now) >= LOSSY_RATE) 2 else 1
    }

    /** When the tag becomes silent (ms): the expected time plus the tolerated periods and the margin. */
    fun silentAt(t: Tracker, now: Long): Long? {
        val p = t.last ?: return null
        val next = nextAt(t, now) ?: return null
        return next + allowedMissed(t, now) * intervalS(t, p, now) * 1000L + SILENT_MARGIN_MS
    }

    const val LOSSY_RATE = 0.25

    /** "LOST", "TRACK" or "IDLE": the mode the tag runs now (a Lost or Track that ran out is IDLE). */
    fun modeNow(t: Tracker, now: Long): String {
        val m = t.last?.mode ?: return "IDLE"
        val live = t.modeUntil == null || now < t.modeUntil
        return if (live && (m == "LOST" || m == "TRACK")) m else "IDLE"
    }

    /**
     * When the current quiet stretch began (ms): the oldest of the newest run of quiet reports with
     * the same reason. Null when the newest report is not quiet.
     */
    fun quietSince(t: Tracker): Long? {
        val reps = t.positions.filter { it.via != "usb-quiet" }
        val last = reps.lastOrNull()?.takeIf { it.quiet } ?: return null
        var since = last.receivedAt
        for (i in reps.size - 2 downTo 0) {
            val p = reps[i]
            if (!p.quiet || p.quietReason != last.quietReason) break
            since = p.receivedAt
        }
        return since
    }

    /**
     * The checks of the current quiet stretch, when the last report was quiet with CHECKS and the
     * app knows the interval (it computed the plan for that very report).
     */
    fun checkPlan(t: Tracker): CheckPlan? {
        val p = t.last ?: return null
        // an approximate time can't anchor the checks: only the heartbeat counts then
        if (!p.checks || !t.intervalKnown || p.approx) return null
        if (!p.quiet && !(t.reportEvery > 1 && p.mode == "IDLE")) return null
        return t.checkPlan?.takeIf { it.n == p.n }
    }

    /** The zone a position lies in (its fix or, without one, the last fix it carries). */
    fun zoneOf(t: Tracker, p: Position): Zone? {
        val (lat, lon) = when {
            p.hasFix -> p.lat to p.lon
            p.lastFixTime > 0 -> p.lastLat to p.lastLon
            else -> return null
        }
        return t.zones.firstOrNull { app.libretag.locate.Geo.distanceM(lat, lon, it.lat, it.lon) <= it.radiusM }
    }

    /**
     * The zone a quiet "zone" report stands for: a GPS zone (its fix, or the last fix it carries),
     * else, from a Wi-Fi tag without a fix, its Wi-Fi zone: the name when the tag has only one,
     * else [WifiZoneTransit.UNNAMED]. Null: unknown (shown as Home).
     */
    fun quietZoneName(t: Tracker, p: Position): String? {
        zoneOf(t, p)?.let { return it.name }
        if (p.hasFix || !(t.wifiTag || p.wifi != null)) return null
        return wifiZoneName(t) ?: WifiZoneTransit.UNNAMED
    }

    /** The Wi-Fi zone's name when the tag has exactly one (a report doesn't say which), else null. */
    fun wifiZoneName(t: Tracker): String? = t.wifiZones.singleOrNull()?.name

    /** List order: alarms first, then by age (most recently heard first). */
    fun sort(list: List<Tracker>, now: Long = System.currentTimeMillis()): List<Tracker> =
        list.map { it to of(it, now) }
            .sortedWith(compareBy<Pair<Tracker, TagStatus>> { it.second.priority }.thenByDescending { it.second.heardAt ?: 0L })
            .map { it.first }
}

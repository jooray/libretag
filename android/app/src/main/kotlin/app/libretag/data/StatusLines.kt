package app.libretag.data

/**
 * Which sentence each block of a tag's status shows, and with which times (docs: ui-review §3).
 * Pure, so the rules that keep times from conflicting are unit tested; `ui/TagText` turns it into
 * words.
 *
 * The rules:
 * 1. One future time per line, one meaning per block. Report times only in [Lines.next]; the time
 *    a command gets through only in [Lines.reach]. A listen time ("check") never appears as a
 *    clock time anywhere else.
 * 2. A listen interval is a duration ([Next.withinS]), never a second clock time; dropped when the
 *    tag does not listen between reports.
 * 3. [Ahead.deadline] false is an estimate ("~"), true is a deadline ("at the latest"); a quiet
 *    tag's sign of life is always a deadline.
 * 4. The heard time is never on the plate's second line (only in the When row and the list's age).
 * 5. Low battery never replaces the state on the detail plate (Status.of(lowBattery = false)).
 * 6. An approximate report (a bridge drained it late): deadlines only, no listen bracket.
 */
object StatusLines {
    /** A time ahead: an estimate ("~14:31") or a deadline ("14:31 at the latest"). */
    data class Ahead(val at: Long, val deadline: Boolean)

    enum class Plate {
        WAITING, HEARD, HEARD_LATE,
        /** 0.12: one (or two) expected reports missed, inside the tolerance; the next one is projected */
        MISSED,
        LEFT, QUIET_PLACE, QUIET_WITH_YOU, QUIET_STILL, QUIET_PARKED,
        TRACKING, LOST, GUARD_ALARM, NOT_HEARD, LOW_BATTERY,
    }

    enum class SubKind { NONE, PAIRED, SINCE, SINCE_DEVICE, LOST_UNTIL, GUARD_LEFT, NOT_HEARD }

    /** The plate's second line: a past moment ([past]) or what ends the state ([ahead], Lost only). */
    data class Sub(val kind: SubKind, val past: Long? = null, val ahead: Long? = null, val approx: Boolean = false)

    enum class NextKind { NONE, FIRST_WITHIN, REPORT, DUE, LATEST, WHEN_LEAVES, WHEN_PART, WHEN_MOVES, OVERDUE }

    /** The When row's one "next report" line. [withinS]: the listen interval in brackets, [intervalS] for FIRST_WITHIN. */
    data class Next(val kind: NextKind, val ahead: Ahead? = null, val withinS: Int? = null, val intervalS: Int? = null)

    enum class ReachKind { AT, WITH_REPORT, WHEN_HEARD, FIRST }

    /** The one line under the commands: when a command reaches the tag. */
    data class Reach(val kind: ReachKind, val ahead: Ahead? = null)

    data class Lines(
        val plate: Plate,
        /** the only future time a plate's first line may hold: Tracking "until" */
        val plateAhead: Long?,
        val sub: Sub,
        val next: Next,
        val reach: Reach,
        /** QUIET_PLACE / LEFT: the place's name (null = unknown, shown as Home) */
        val place: String? = null,
    )

    fun of(t: Tracker, s: TagStatus, now: Long): Lines {
        val plate = plate(t, s, now)
        val place = when (plate) {
            Plate.QUIET_PLACE -> s.quietWhere
            Plate.LEFT -> s.leftPlace
            else -> null
        }
        return Lines(plate, if (plate == Plate.TRACKING) s.until else null, sub(t, s, plate), next(t, s, plate, now), reach(s), place)
    }

    fun plate(t: Tracker, s: TagStatus, now: Long): Plate = when (s.kind) {
        StatusKind.WAITING -> Plate.WAITING
        StatusKind.LOST -> Plate.LOST
        StatusKind.ALARM -> Plate.GUARD_ALARM
        StatusKind.SILENT -> Plate.NOT_HEARD
        StatusKind.LOW_BATTERY -> Plate.LOW_BATTERY
        StatusKind.TRACKING -> Plate.TRACKING
        StatusKind.QUIET -> when (s.quietWhere) {
            "owner" -> Plate.QUIET_WITH_YOU
            "parked" -> if (t.type == "vehicle") Plate.QUIET_PARKED else Plate.QUIET_STILL
            else -> Plate.QUIET_PLACE
        }
        StatusKind.LEFT -> Plate.LEFT
        StatusKind.OK -> if (s.missed > 0) Plate.MISSED else if (late(s, now)) Plate.HEARD_LATE else Plate.HEARD
    }

    /** an exact next report whose estimate has passed (still inside the 30 min margin) */
    fun late(s: TagStatus, now: Long): Boolean = !s.approx && s.nextAt != null && s.nextAt < now

    private fun sub(t: Tracker, s: TagStatus, plate: Plate): Sub = when (plate) {
        Plate.WAITING -> if (t.createdAt > 0) Sub(SubKind.PAIRED, past = t.createdAt) else Sub(SubKind.NONE)
        Plate.QUIET_PLACE, Plate.QUIET_STILL, Plate.QUIET_PARKED ->
            Sub(SubKind.SINCE, past = Status.quietSince(t) ?: s.heardAt, approx = s.approx)
        Plate.QUIET_WITH_YOU -> Sub(SubKind.SINCE_DEVICE, past = Status.quietSince(t) ?: s.heardAt, approx = s.approx)
        Plate.LOST -> s.until?.let { Sub(SubKind.LOST_UNTIL, ahead = it) } ?: Sub(SubKind.NONE)
        Plate.GUARD_ALARM -> Sub(SubKind.GUARD_LEFT, past = guardAt(s.alarm))
        Plate.NOT_HEARD -> Sub(SubKind.NOT_HEARD, past = s.heardAt, approx = s.approx)
        else -> Sub(SubKind.NONE)
    }

    /** "guard:<ms>" -> ms */
    fun guardAt(alarm: String?): Long? = alarm?.substringAfter(':', "")?.toLongOrNull()

    private fun next(t: Tracker, s: TagStatus, plate: Plate, now: Long): Next {
        if (plate == Plate.WAITING) return Next(NextKind.FIRST_WITHIN, intervalS = t.reportPeriodMin * 60)
        if (plate == Plate.NOT_HEARD) return Next(NextKind.OVERDUE)
        val at = s.nextAt ?: return Next(NextKind.NONE)
        if (s.kind == StatusKind.QUIET) {
            val within = s.checkEveryS.takeIf { !s.approx }
            val kind = when (plate) {
                Plate.QUIET_WITH_YOU -> NextKind.WHEN_PART
                Plate.QUIET_STILL, Plate.QUIET_PARKED -> NextKind.WHEN_MOVES
                else -> NextKind.WHEN_LEAVES
            }
            return Next(kind, Ahead(at, deadline = true), withinS = within)
        }
        if (s.approx) return Next(NextKind.LATEST, Ahead(at, deadline = true))
        if (at < now) return Next(NextKind.DUE, Ahead(at, deadline = false))
        return Next(NextKind.REPORT, Ahead(at, deadline = false))
    }

    private fun reach(s: TagStatus): Reach = when {
        s.kind == StatusKind.WAITING -> Reach(ReachKind.FIRST)
        s.kind == StatusKind.SILENT -> Reach(ReachKind.WHEN_HEARD)
        s.nextCheckAt != null -> Reach(ReachKind.AT, Ahead(s.nextCheckAt, deadline = false))
        s.nextAt != null && (s.kind == StatusKind.QUIET || s.approx) -> Reach(ReachKind.WITH_REPORT, Ahead(s.nextAt, deadline = true))
        s.nextAt != null -> Reach(ReachKind.AT, Ahead(s.nextAt, deadline = false))
        else -> Reach(ReachKind.WHEN_HEARD)
    }
}

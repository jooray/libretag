package app.libretag.data

import app.libretag.locate.Geo
import app.libretag.proto.QuietMesh

/**
 * The app's quiet mesh rules (SPEC.md 3.2 "The owner's node and the app", PROTOCOL.md 2.6, 3, 4.3),
 * pure so they are unit tested: the anchor and its grids, the ACK sender's choice, command hygiene
 * (P12: R5 to R8, R10), the silence deadline by link and the notices of A3.
 */
object QuietPlan {
    // ---------------------------------------------------------------- the anchor (R1, R32)

    /** slots a grid keeps: enough for 50 h (the longest entry lifetime) at its interval */
    fun slotCount(intervalMin: Int): Int = if (intervalMin <= 0) 0 else (50 * 60 / intervalMin + 2).coerceIn(4, 400)

    /**
     * The anchor after report [n] copy [j] at exact time [t] (ms). [grid] builds the slot grid of a
     * report (it needs the command chain): called only for grids that are new.
     */
    fun anchorAfter(s: AnchorState, n: Long, j: Int, t: Long, sync: Boolean, liveAck: Boolean, still: Boolean,
                    dueInGap: Boolean, x: String?, grid: (Long, Long) -> CheckPlan?): AnchorState {
        val before = toApp(s)
        val after = before.onReport(n, j, t, sync, liveAck, still, dueInGap)
        return fromApp(after, s, grid).let { st ->
            // the x of this copy is one more `via` of the grid of n
            if (x == null) st else st.copy(
                a = st.a?.let { if (it.n == n && x !in it.vias) it.copy(vias = (it.vias + x).takeLast(5)) else it },
                cands = st.cands.map { if (it.n == n && x !in it.vias) it.copy(vias = (it.vias + x).takeLast(5)) else it },
            )
        }
    }

    /**
     * A live ACK for report [n] went out only on its second sighting (the first copy could not get
     * one: the node lacked rx_meta, or a relay copy came after a node copy). The tag takes it in the
     * window of n and anchors on n's first transmission, so n becomes a candidate with the time of
     * the first copy the app had (PROTOCOL.md 2.6, AppAnchor's second-sighting rule).
     */
    fun liveAckOnSecond(s: AnchorState, n: Long, j: Int, t: Long, sync: Boolean, grid: (Long, Long) -> CheckPlan?): AnchorState =
        if (n != s.lastN) s else anchorAfter(s, n, j, t, sync, liveAck = true, still = true, dueInGap = false, x = null, grid = grid)

    fun confirmed(s: AnchorState, nGrid: Long): AnchorState = fromApp(toApp(s).onCommandConfirmed(nGrid), s) { _, _ -> null }

    private fun toApp(s: AnchorState) = QuietMesh.AppAnchor(
        a = s.a?.let { QuietMesh.Grid(it.n, it.t0) }, cands = s.cands.map { QuietMesh.Grid(it.n, it.t0) },
        lastN = s.lastN, seen = s.seen, known = s.known,
    )

    private fun fromApp(a: QuietMesh.AppAnchor, old: AnchorState, grid: (Long, Long) -> CheckPlan?): AnchorState {
        val have = old.grids.associateBy { it.n to it.t0 }
        fun g(x: QuietMesh.Grid): CheckPlan? = have[x.n to x.t0] ?: grid(x.n, x.t0)
        return AnchorState(a = a.a?.let { g(it) }, cands = a.cands.mapNotNull { g(it) }, lastN = a.lastN, seen = a.seen, known = a.known)
    }

    /**
     * The tag stands where it stood: a fix in both reports within max(MOVE_M, 3 x accuracy), and no
     * BOOT. Without a fix it is not known to stand.
     */
    fun still(prev: Position?, cur: Position): Boolean {
        if (prev == null || cur.boot || !cur.hasFix || !prev.hasFix) return false
        val d = Geo.distanceM(prev.lat, prev.lon, cur.lat, cur.lon)
        return d <= QuietMesh.moveM(maxOf(prev.haccM ?: 0, cur.haccM ?: 0))
    }

    /** A heartbeat (24 h) or the hold flood fell due between two reports [gapMs] apart. */
    fun dueInGap(gapMs: Long, holdUnits: Int, periodS: Long): Boolean {
        if (gapMs >= 24 * 3600_000L) return true
        val h = QuietMesh.holdS(holdUnits, periodS) ?: return gapMs > periodS * 1000
        return gapMs >= h * 1000
    }

    // ---------------------------------------------------------------- the ACK sender (SPEC.md 3.2, A2)

    enum class AckWay { NODE, NOSTR, TIMED, SKIP }

    data class AckChoice(val way: AckWay, val form: QuietMesh.Form, val why: String)

    /**
     * What the ACK sender does for an opened report. [newest]: the newest report of the tag;
     * [approx]: only an approximate time (a drained frame, the first 20 s after the node
     * connected); [canCommand]: this phone holds C_e (a view share cannot ACK); [viaNode]: the copy
     * came through the own node; [nodeDirect]: the node heard it directly (copy 9 or a flood with
     * 0 hops); [rxMeta], [zeroHop]: the node's server offers `rx_meta` and `tx_path_data`;
     * [ageMs]: the report's age by its radio time; [standing]: the fix is within MOVE_M of the
     * previous report's.
     */
    fun ackChoice(newest: Boolean, approx: Boolean, canCommand: Boolean, j: Int, viaNode: Boolean, nodeDirect: Boolean,
                  rxMeta: Boolean, zeroHop: Boolean, ageMs: Long, standing: Boolean): AckChoice {
        val form = QuietMesh.formFor(j, standing)
        if (!canCommand) return AckChoice(AckWay.SKIP, form, "view_only")
        if (!newest) return AckChoice(AckWay.SKIP, form, "older")
        if (approx) return AckChoice(AckWay.SKIP, form, "approx")
        if (j !in QuietMesh.J_ON_AIR) return AckChoice(AckWay.SKIP, form, "copy")
        if (ageMs >= QuietMesh.LIVE_ACK_MAX_AGE_MS) return AckChoice(AckWay.TIMED, form, "late")
        if (viaNode) {
            // the own node sends zero-hop only, and only with rx_meta (it knows the copy came directly)
            if (nodeDirect && rxMeta && zeroHop && j != QuietMesh.J_ROUTED) return AckChoice(AckWay.NODE, QuietMesh.Form.NEAR, "node")
            // a relay copy of the same report (second sighting) makes the entry for the bridge that heard it
            return AckChoice(AckWay.SKIP, form, "wait_relay")
        }
        return AckChoice(AckWay.NOSTR, form, "relay")
    }

    /**
     * MORE (PROTOCOL.md 3.3): a command follows, keep the window open. While a command waits, while
     * the tag's screen is open, and for 30 s after a notification about it.
     */
    fun more(pending: Boolean, screenOpen: Boolean, notifiedAt: Long?, now: Long): Boolean =
        pending || screenOpen || (notifiedAt != null && now - notifiedAt in 0 until 30_000L)

    /**
     * The slots of timed ACKs for report [n] opened late: on each grid the next slots after [nowMs]
     * (2 for the near form, 1 for a routed form). Pairs (grid, k).
     */
    fun timedAckSlots(grids: List<CheckPlan>, form: QuietMesh.Form, nowMs: Long): List<Pair<CheckPlan, Int>> {
        val count = if (form == QuietMesh.Form.ROUTED) QuietMesh.TIMED_ACK_SLOTS_ROUTED else QuietMesh.TIMED_ACK_SLOTS_NEAR
        val out = mutableListOf<Pair<CheckPlan, Int>>()
        for (g in grids) {
            var c = 0
            for ((i, off) in g.offsetsS.withIndex()) {
                // the send at t_k + 1 s has to be a few seconds ahead to reach a bridge in time
                if (g.t0 + (off + QuietMesh.SLOT_SEND_AFTER_S) * 1000 <= nowMs + 10_000L) continue
                out += g to (i + 1)
                if (++c >= count) break
            }
        }
        return out
    }

    // ---------------------------------------------------------------- command hygiene (P12)

    /**
     * R6: a random cmd_seq 1..255 whose low four bits differ from the tag's newest report's and
     * from this device's previous command, so a second or restored device never repeats the tag's
     * last executed number. [rnd] gives 0..254.
     */
    fun pickSeq(tagLow: Int?, prevSeq: Int, rnd: (Int) -> Int): Int {
        val avoid = setOfNotNull(tagLow?.and(0x0F), prevSeq.takeIf { it > 0 }?.and(0x0F))
        repeat(64) {
            val s = 1 + rnd(255)
            if ((s and 0x0F) !in avoid) return s
        }
        return (1..255).first { (it and 0x0F) !in avoid }
    }

    /**
     * R5: report [n] confirms [p] only when its counter is above the newest one when the command was
     * queued, it is the newest report, it has CMD_ACK with the command's low four bits, and for Lost,
     * Track and Normal the mode the command asked for.
     */
    fun confirms(p: PendingCommand, n: Long, newest: Boolean, cmdAck: Boolean, cmdSeq: Int, mode: String): Boolean {
        if (!newest || !cmdAck || (p.seq and 0x0F) != (cmdSeq and 0x0F)) return false
        if (p.queuedAtN >= 0 && n <= p.queuedAtN) return false
        val want = when (p.kind) {
            "LOST" -> "LOST"
            "TRACK" -> "TRACK"
            "NORMAL" -> "IDLE"
            else -> null
        }
        return want == null || want == mode
    }

    /** R7: the command's lifetime ran out. */
    fun expired(p: PendingCommand, now: Long): Boolean = p.expiresAt > 0 && now >= p.expiresAt

    // ---------------------------------------------------------------- link and silence (A3, 4.4)

    enum class Link { NEAR_NODE, NEAR_BRIDGE, ROUTED, FAR, UNKNOWN }

    /**
     * The tag's link as the app sees it from its last report and the last ACK: a copy 11 is ROUTED;
     * a hello or a flood the app ACKed live (or the node heard directly) is NEAR; a flood nobody
     * answered is FAR.
     */
    fun link(t: Tracker): Link {
        val p = t.last ?: return Link.UNKNOWN
        if (p.j < 0) return Link.UNKNOWN
        val acked = t.lastAck?.let { it.n == p.n && !it.timed } == true
        return when {
            p.j == QuietMesh.J_ROUTED -> Link.ROUTED
            p.nodeDirect && (acked || p.j == QuietMesh.J_NEAR) -> Link.NEAR_NODE
            p.j == QuietMesh.J_NEAR || acked -> Link.NEAR_BRIDGE
            else -> Link.FAR
        }
    }

    /**
     * When "silent" is raised (SPEC.md 3.2 "Silence"): NEAR max(next + tolerance, t_ack + hold +
     * period) + 30 min; FAR next_in of the last flood + tolerance; ROUTED that plus one check
     * interval; a view-only share next_in of the last report + 24 h. [baseDeadline]: the deadline
     * the plain schedule gives (next + tolerance + 30 min, [Status.silentAt]).
     */
    fun silentDeadline(t: Tracker, baseDeadline: Long?, now: Long): Long? {
        val p = t.last ?: return baseDeadline
        if (!t.canCommand) return p.nextInS?.let { (p.approxUntil ?: p.receivedAt) + it * 1000 + 24 * 3600_000L } ?: baseDeadline
        val base = baseDeadline ?: return null
        if (Status.modeNow(t, now) != "IDLE") return base
        val periodMs = t.reportPeriodMin * 60_000L
        return when (link(t)) {
            Link.NEAR_NODE, Link.NEAR_BRIDGE -> {
                val ack = t.lastAck
                val hs = ack?.let { QuietMesh.holdS(it.hold, periodMs / 1000) }
                if (ack == null || hs == null) base
                else maxOf(base, ack.at + hs * 1000 + periodMs + Status.SILENT_MARGIN_MS)
            }
            Link.ROUTED -> base + t.intervalMin * 60_000L
            else -> base
        }
    }

    // ---------------------------------------------------------------- notices (A3)

    /** IDLE flood budget (SPEC.md 3.2): 8 in 24 h; the app counts the flood copies it opened. */
    const val IDLE_FLOOD_BUDGET = 8

    /**
     * R12 "reports limited until …": the app opened 8 IDLE flood reports in the last 24 h (events and
     * command answers not counted) and the last one's next_in reaches past the usual period. The
     * time the budget frees, else null.
     */
    fun limitedUntil(t: Tracker, now: Long): Long? {
        val day = t.positions.filter { it.n >= 0 && it.flood && it.mode == "IDLE" && !it.cmdAck && !it.boot && now - it.receivedAt in 0..86_400_000L }
            .distinctBy { it.n }
        if (day.size < IDLE_FLOOD_BUDGET) return null
        val last = day.maxBy { it.n }
        if (t.last?.n != last.n) return null
        val next = last.nextInS ?: return null
        if (next * 1000 <= t.reportPeriodMin * 60_000L * 3 / 2) return null
        val until = last.receivedAt + next * 1000
        return until.takeIf { it > now }
    }

    /** R15: Lost or Track ran out (the end time the app set) and the owner was not told yet. */
    fun modeEnded(t: Tracker, now: Long): Long? {
        val until = t.modeUntil ?: return null
        val m = t.last?.mode ?: return null
        if (m != "LOST" && m != "TRACK") return null
        if (now < until || t.modeEndNotified == until) return null
        return until
    }

    /**
     * "<Tag> is heard but does not get the answers": the app ACKed the last two reports live and no
     * bridge said it sent one ([Tracker.acksNoNote]), or floods keep coming from the same place
     * after hold ran out.
     */
    fun noAnswers(t: Tracker): Boolean {
        if (!t.canCommand) return false
        if (t.acksNoNote >= 2) return true
        val r = t.positions.filter { it.n >= 0 && it.via != "usb" && it.via != "usb-quiet" }.takeLast(3)
        if (r.size < 3 || !r.all { it.flood && it.mode == "IDLE" && !it.cmdAck }) return false
        // the app answered the floods (at least the one before the last), yet they keep coming from one place
        val acked = t.lastAck?.let { a -> !a.timed && a.n >= r[1].n } == true
        return acked && r.zipWithNext().all { (a, b) -> still(a, b) }
    }

    /**
     * R14: the battery is probably empty: the last report said LOW_BATT or 10 % and below, and it is
     * overdue. Since when (ms), null otherwise.
     */
    fun batteryEmptySince(t: Tracker): Long? {
        val p = t.last ?: return null
        val b = p.battery
        return if (p.lowBatt || (b != null && b <= CritBattery.PCT)) p.receivedAt else null
    }

    /**
     * "Your MeshCore node is off": the last report came through the own node directly, the node is
     * down now and the next hello is overdue. The time the tag falls back to the wider mesh by
     * (t_ack + hold), or null.
     */
    fun nodeOffBy(t: Tracker, nodeDown: Boolean, now: Long): Long? {
        val p = t.last ?: return null
        if (!nodeDown || p.via != "mesh" || !p.nodeDirect) return null
        val next = Status.nextAt(t, now) ?: return null
        if (now <= next) return null
        val a = t.lastAck ?: return next
        val hs = QuietMesh.holdS(a.hold, t.reportPeriodMin * 60L) ?: return next
        return a.at + hs * 1000
    }
}

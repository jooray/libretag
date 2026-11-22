package app.libretag.ble

import app.libretag.CommandKind
import app.libretag.data.CheckPlan
import app.libretag.data.Tracker
import app.libretag.proto.BleCommand

/** Which sealing of the pending command the phone advertises now: (n, k), valid until [untilMs] (null: until a new report). */
data class BleTarget(val n: Long, val k: Int, val untilMs: Long?)

/**
 * Commands over Bluetooth (PROTOCOL.md 3.1). The tag scans for the command advert at every wake-up
 * and takes it sealed for the command it would take from the mesh at that moment: check k of report n, or [BleCommand.K_NEXT] when the wake-up is not a
 * check (normal reporting, the heartbeat of a quiet stretch, LOST). The phone advertises the
 * sealing for the next such wake-up and moves on when its scan is over, so the advert changes at
 * every check and an observer cannot follow one payload for hours.
 */
object BleBeaconPlan {
    /**
     * After t_k the tag's scan of check k is over: the presence scan lasts at most 30 s, plus the
     * 2 s grace after the owner's device answers, plus the flood delay of report n (the app anchors
     * t0 when it received the report, the tag when it sent it).
     */
    const val SCAN_AFTER_MS = 45_000L

    /** the tag's clock guard at offset [offS] (2 s + 100 ppm, PROTOCOL.md 2.1) */
    fun guardMs(offS: Long): Long = 2_000L + offS * 1000L / 10_000L

    /** Can this tag take commands from this phone's adverts at all? */
    fun eligible(t: Tracker): Boolean = t.canCommand && t.lastN >= 0

    /** The command's mailbox lifetime: after it the phone stops advertising too. */
    fun expiresAt(queuedAt: Long, kind: String): Long =
        queuedAt + (CommandKind.values().firstOrNull { it.name.equals(kind, true) }?.mailboxTtlS ?: (48 * 3600L)) * 1000L

    /**
     * 0.12 (docs/design/tag-diagnosis.md 4.1): while the tag is late, the same command sealed for
     * report n + 1 as well, in case n + 1 went out and was lost (the one QUIET report before a long
     * quiet stretch): k = K_NEXT and, with a known interval, the checks of n + 1 around now, counted
     * from t0 = the expected time of n + 1 ([offsetsOf] gives the check offsets of a report, s).
     * At most 4 more advertising sets. Needs a bench check of the rotation on phones with few sets.
     */
    fun ahead(t: Tracker, t0: Long?, now: Long, offsetsOf: (Long) -> List<Long>?): List<BleTarget> {
        if (t.pending == null || !eligible(t)) return emptyList()
        val n1 = t.lastN + 1
        val out = mutableListOf(BleTarget(n1, BleCommand.K_NEXT, null))
        if (t0 == null || !t.intervalKnown) return out
        val offs = offsetsOf(n1) ?: return out
        val i = offs.indexOfFirst { off -> t0 + off * 1000L + guardMs(off) + SCAN_AFTER_MS > now }
        if (i < 0) return out
        for (j in (i - 1)..(i + 1)) {
            if (j !in offs.indices) continue
            val off = offs[j]
            out += BleTarget(n1, j + 1, t0 + off * 1000L + guardMs(off) + SCAN_AFTER_MS)
        }
        return out
    }

    /** [plan]: the check plan of the last report (Status.checkPlan), null when there is none. */
    fun target(t: Tracker, plan: CheckPlan?, now: Long): BleTarget? {
        val p = t.pending ?: return null
        if (!eligible(t)) return null
        if (p.queuedAt > 0 && now >= expiresAt(p.queuedAt, p.kind)) return null
        val n = t.lastN
        if (plan == null || plan.n != n) return BleTarget(n, BleCommand.K_NEXT, null)
        plan.offsetsS.forEachIndexed { i, off ->
            val end = plan.t0 + off * 1000L + guardMs(off) + SCAN_AFTER_MS
            if (end > now) return BleTarget(n, i + 1, end)
        }
        // all checks before the next report are over: quiet, the heartbeat (not a check); between
        // reports (report_every > 1), keyed wake-up R, the one that reports
        return if (t.last?.quiet == true) BleTarget(n, BleCommand.K_NEXT, null)
        else BleTarget(n, plan.offsetsS.size + 1, null)
    }
}

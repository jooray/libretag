package app.libretag.data

/**
 * What the gaps between report counters say (docs/design/tag-diagnosis.md 4.3). At boot the tag
 * continues at `max(state n_next, key page n_floor, 64 × epoch)`: the state checkpoint moves in
 * steps of 16, the floor in steps of 32, and after a quiet stretch of 12 h or more `n` jumps to
 * the next epoch (a multiple of 64). Pure, unit tested.
 */
object CounterGaps {
    enum class Kind {
        /** d == 1 */
        NORMAL,
        /** the jump to the next epoch after a quiet stretch of 12 h or more: normal */
        EPOCH_SKIP,
        /** a restart in between (the counter landed on a checkpoint sooner than lost reports explain) */
        RESTART,
        /** d − 1 reports lost on the way */
        LOST,
    }

    data class Gap(val a: Position, val b: Position, val kind: Kind) {
        val d: Long get() = b.n - a.n
        /** reports lost on the way, counted at most [MAX_LOST_PER_GAP] per gap (a long outage is a silence, not loss) */
        val lost: Int get() = if (kind == Kind.LOST) (d - 1).coerceAtMost(MAX_LOST_PER_GAP.toLong()).toInt() else 0
    }

    const val EPOCH = 64L
    const val CHECKPOINT = 16L
    const val QUIET_SKIP_MS = 12 * 3600_000L
    const val MAX_LOST_PER_GAP = 8
    const val LOSS_WINDOW_MS = 7 * 86_400_000L

    /** The reports with a counter, in counter order (fixes kept while quiet are not reports). */
    fun reports(t: Tracker): List<Position> =
        t.positions.filter { it.n >= 0 && it.via != "usb-quiet" }.distinctBy { it.n }.sortedBy { it.n }

    /** The time between two reports the tag meant (ms), from the first report of the pair. */
    fun periodMs(t: Tracker, a: Position): Long {
        if (!a.quiet) a.nextInS?.takeIf { it > 0 }?.let { return it * 1000 }
        return when (a.mode) {
            "LOST" -> (t.modeIntervalS ?: Status.LOST_INTERVAL_S) * 1000L
            "TRACK" -> (t.modeIntervalS ?: Status.TRACK_INTERVAL_S) * 1000L
            else -> t.reportPeriodMin * 60_000L
        }.coerceAtLeast(60_000L)
    }

    fun classify(t: Tracker, a: Position, b: Position): Gap {
        val d = b.n - a.n
        if (d <= 1) return Gap(a, b, Kind.NORMAL)
        val dt = b.receivedAt - a.receivedAt
        if (b.n % EPOCH == 0L && a.quiet && dt >= QUIET_SKIP_MS) return Gap(a, b, Kind.EPOCH_SKIP)
        // the boot report itself says so (firmware 0.9.4, BOOT bit)
        if (b.boot) return Gap(a, b, Kind.RESTART)
        val per = periodMs(t, a)
        if (b.n % CHECKPOINT == 0L && dt < (d - 1) * per / 2) return Gap(a, b, Kind.RESTART)
        return Gap(a, b, Kind.LOST)
    }

    fun gaps(t: Tracker): List<Gap> {
        val r = reports(t)
        return (1 until r.size).map { classify(t, r[it - 1], r[it]) }
    }

    /**
     * The share of reports lost on the way over the last 7 days: lost / (received + lost). Only
     * gaps between two reports both inside the window count. 0 when there is too little history.
     */
    fun lossRate(t: Tracker, now: Long): Double {
        val r = reports(t).filter { now - it.receivedAt in 0..LOSS_WINDOW_MS }
        if (r.size < 4) return 0.0
        var lost = 0
        for (i in 1 until r.size) lost += classify(t, r[i - 1], r[i]).lost
        return lost.toDouble() / (r.size + lost)
    }

    /** "3 of 161": lost and expected over the last 7 days. */
    fun lossCount(t: Tracker, now: Long): Pair<Int, Int> {
        val r = reports(t).filter { now - it.receivedAt in 0..LOSS_WINDOW_MS }
        var lost = 0
        for (i in 1 until r.size) lost += classify(t, r[i - 1], r[i]).lost
        return lost to (r.size + lost)
    }

    /** Restarts the counters show (no reset cause), newest last. */
    fun inferredBoots(t: Tracker): List<BootRec> = gaps(t).filter { it.kind == Kind.RESTART }
        .map { BootRec(at = it.b.receivedAt, n = it.b.n, cause = it.b.bootCause, resets = it.b.bootResets, inferred = !it.b.boot) }

    /**
     * A report that opens with a counter below the newest one and other content than the one
     * stored under the same counter: the tag's counter went back (a restored state, a second tag
     * with the same key). A late copy of the same report is not.
     */
    fun counterBack(existing: List<Position>, p: Position): Boolean {
        if (p.n < 0) return false
        val newest = existing.filter { it.n >= 0 && it.via != "usb-quiet" }.maxOfOrNull { it.n } ?: return false
        if (p.n > newest - 1) return false
        val same = existing.firstOrNull { it.n == p.n && it.via != "usb-quiet" } ?: return false
        return same.fixTime != p.fixTime || same.battery != p.battery || same.mode != p.mode ||
            (same.hasFix && p.hasFix && (same.lat != p.lat || same.lon != p.lon))
    }
}

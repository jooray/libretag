package app.libretag.data

/**
 * How a copy of report n joins the history (0.8.1). Copies come from our radio, from several
 * bridges and from relay backlogs. A copy a bridge drained late from a companion node's queue
 * carries only a time range ([Position.approx], PROTOCOL.md 4): an exact copy of the same report
 * always wins over it, and two approximate copies narrow the range to what both allow.
 */
object ReportMerge {
    private const val VIA_USB_QUIET = "usb-quiet"

    /** the copy of report [n] already in [positions], if any */
    fun existing(positions: List<Position>, n: Long): Position? =
        if (n < 0) null else positions.lastOrNull { it.n == n && it.via != VIA_USB_QUIET }

    /** [pos] adds nothing: an exact copy of the same report is already there */
    fun redundant(positions: List<Position>, pos: Position): Boolean =
        pos.approx && existing(positions, pos.n)?.approx == false

    /**
     * [pos] is the first exact copy of the newest report, which so far was known only from an
     * approximate copy: the check schedule can now be anchored on it.
     */
    fun upgrades(t: Tracker, pos: Position): Boolean =
        !pos.approx && pos.n >= 0 && pos.n == t.lastN && existing(t.positions, pos.n)?.approx == true

    /** [pos] with the narrower time range when both it and the copy before it are approximate */
    fun narrowed(positions: List<Position>, pos: Position): Position {
        val old = existing(positions, pos.n) ?: return pos
        if (!pos.approx || !old.approx) return pos
        val from = maxOf(old.receivedAt, pos.receivedAt)
        val until = minOf(old.approxUntil!!, pos.approxUntil!!)
        return if (from <= until) pos.copy(receivedAt = from, approxUntil = until) else pos
    }

    /** the history with [pos] in place of any earlier copy of the same report, sorted by counter */
    fun merge(positions: List<Position>, pos: Position, max: Int): List<Position> {
        if (redundant(positions, pos)) return positions
        val p = narrowed(positions, pos)
        val n = p.n
        return (positions.filter { it.n != n || n < 0 || it.via == VIA_USB_QUIET } + p)
            .sortedWith(compareBy({ it.n >= 0 }, { it.n }))
            .takeLast(max)
    }
}

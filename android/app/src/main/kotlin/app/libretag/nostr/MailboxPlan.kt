package app.libretag.nostr

/** How one command's mailbox entries go out (PROTOCOL.md 4, 4.2). */
object MailboxPlan {
    /** the tag listens this long after a report (firmware default `listen` 90 s) */
    const val LISTEN_S = 90L
    /** a late entry has to be on the relays and at a bridge before the window closes */
    const val LATE_MARGIN_S = 15L

    /** ["g", 16 lowercase hex]: one random group id per command, on every entry of it */
    fun newGroup(rnd: java.util.Random = java.security.SecureRandom()): String =
        ByteArray(8).also { rnd.nextBytes(it) }.hex()

    /**
     * A late entry (PROTOCOL.md 4.2): a command queued while the tag still listens after report n
     * goes into an untimed entry for n itself; a bridge whose radio heard n and locked its key sends
     * it at once. [ageS] = seconds since report n arrived.
     */
    fun lateOk(ageS: Long, listenS: Long = LISTEN_S): Boolean = ageS in 0 until (listenS - LATE_MARGIN_S)

    /** Untimed first, then timed by ascending `at`; untimed among themselves by counter. */
    fun <T> order(entries: List<T>, at: (T) -> Long?, n: (T) -> Long): List<T> =
        entries.sortedWith(compareBy<T>({ at(it) != null }, { at(it) ?: n(it) }))

    /**
     * An entry for report [n] is not worth publishing once report [lastN] is in: an untimed one waits
     * for a report that has already come (bridges send only what they knew before the report), a
     * timed one targets the checks after an older report.
     */
    fun stale(n: Long, timed: Boolean, lastN: Long, late: Boolean = false): Boolean =
        if (timed || late) n < lastN else n <= lastN

    /** The tags of an entry: its own tags, the expiration and the command's group. */
    fun tags(base: List<List<String>>, expirationS: Long, group: String): List<List<String>> {
        require(isLowerHex(group, 8)) { "group must be 16 lowercase hex" }
        return base.filter { it.firstOrNull() != "g" && it.firstOrNull() != "expiration" } +
            listOf(listOf("expiration", expirationS.toString()), listOf("g", group))
    }
}

package app.libretag.data

/**
 * "Battery almost empty" (10 %): once per crossing, from either signal.
 * - a report (Nostr via a bridge, or mesh) with battery ≤ [PCT]; firmware 0.7.6 sends one at the
 *   10 % crossing even while quiet;
 * - the tag's own NIP-04 DM to the owner ("batt 9%", PROTOCOL.md 5), which the app can read
 *   because it holds the tag's DM key (NIP-04's shared secret is symmetric).
 * The crossing ends when a report shows more than [RESET_PCT]; DMs older than that moment belong to
 * a past crossing and are ignored (relays keep them 14 days).
 */
object CritBattery {
    const val PCT = 10
    const val RESET_PCT = 20

    private val DM = Regex("^batt (\\d{1,3}) ?%")

    /** The percentage in the tag's low-battery DM, or null for any other text (the app's own DMs too). */
    fun parseDm(text: String): Int? = DM.find(text.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }

    data class Step(val tracker: Tracker, val notify: Boolean)

    fun onReport(t: Tracker, batt: Int?, isNew: Boolean, atMs: Long): Step {
        if (batt == null || !isNew) return Step(t, false)
        if (batt > RESET_PCT) return Step(if (t.critBattNotified || t.critBattResetAt < atMs) t.copy(critBattNotified = false, critBattResetAt = maxOf(t.critBattResetAt, atMs)) else t, false)
        if (batt <= PCT && !t.critBattNotified) return Step(t.copy(critBattNotified = true), true)
        return Step(t, false)
    }

    fun onDm(t: Tracker, pct: Int, createdAtMs: Long): Step {
        if (pct > PCT || t.critBattNotified) return Step(t, false)
        if (createdAtMs <= t.critBattResetAt || createdAtMs < t.createdAt) return Step(t, false)
        return Step(t.copy(critBattNotified = true), true)
    }
}

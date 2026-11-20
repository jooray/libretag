package app.libretag.backup

/**
 * Merging backups between devices (SPEC.md 12.2): per tag (keyed by k0) the newest change wins,
 * where a live tag's time is `updated` and a removed tag's is `removed`. Tombstones are kept for
 * [TOMBSTONE_KEEP_S] after removal, so a device that was offline for a while doesn't bring a
 * removed tag back, and dropped after that.
 *
 * A v1 tag without `updated` counts as changed at its payload's `created`.
 */
object BackupMerge {
    const val TOMBSTONE_KEEP_S = 90L * 86_400

    /** When this entry last changed; [fallback] for v1 entries without `updated`. */
    fun stamp(t: BackupTag, fallback: Long): Long = t.removed ?: t.updated ?: fallback

    /**
     * [sources] in order of preference (this device first): each payload's tags with its `created`
     * as the fallback time. Ties: a live tag beats a tombstone (losing a key is worse than keeping
     * one too long), then the earlier source wins. Every live tag in the result has `updated` set.
     * Order: first appearance across the sources.
     */
    fun merge(sources: List<BackupPayload>, now: Long): List<BackupTag> {
        val best = LinkedHashMap<String, Pair<BackupTag, Long>>()
        for (src in sources) {
            for (t in src.tags) {
                val k = t.k0Hex.lowercase()
                val s = stamp(t, src.created)
                val norm = if (t.isRemoved) BackupTag.tombstone(k, s) else t.copy(k0Hex = k, updated = s)
                val cur = best[k]
                if (cur == null || wins(norm, s, cur.first, cur.second)) best[k] = norm to s
            }
        }
        return best.values.map { it.first }.filter { t -> t.removed == null || now - t.removed < TOMBSTONE_KEEP_S }
    }

    private fun wins(cand: BackupTag, cs: Long, cur: BackupTag, s: Long): Boolean = when {
        cs != s -> cs > s
        cand.isRemoved != cur.isRemoved -> !cand.isRemoved
        else -> false
    }
}

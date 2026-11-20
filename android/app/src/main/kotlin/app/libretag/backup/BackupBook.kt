package app.libretag.backup

/**
 * What this device knows about its own tags for the merge (SPEC.md 12.2): per k0 the time of the
 * last change of what the backup holds (name, type, mark, settings), and tombstones for tags removed here.
 * Rebuilt from the tag list on every change, so no screen has to remember to stamp anything.
 */
data class BackupBook(
    /** k0 -> (fingerprint of name/type/mark, updated unix s) */
    val seen: Map<String, Pair<String, Long>> = emptyMap(),
    /** k0 -> removed unix s */
    val removed: Map<String, Long> = emptyMap(),
) {
    /**
     * After the tag list changed: new or edited tags get [now] (or [createdOf] for a tag seen for
     * the first time, so an old tag doesn't look freshly edited), tags gone from the list become
     * tombstones, and a tag that came back drops its tombstone. Old tombstones expire.
     */
    fun track(current: List<BackupTag>, now: Long, createdOf: (BackupTag) -> Long? = { null }): BackupBook {
        val seen2 = LinkedHashMap<String, Pair<String, Long>>()
        val removed2 = HashMap(removed)
        for (t in current) {
            val k = t.k0Hex.lowercase()
            val fp = fingerprint(t)
            val prev = seen[k]
            seen2[k] = when {
                prev == null && k !in removed -> fp to (createdOf(t)?.takeIf { it in 1..now } ?: now)
                prev == null -> fp to now
                prev.first != fp -> fp to now
                else -> prev
            }
            removed2.remove(k)
        }
        for (k in seen.keys) if (k !in seen2) removed2[k] = now
        removed2.entries.removeAll { now - it.value >= BackupMerge.TOMBSTONE_KEEP_S }
        return BackupBook(seen2, removed2)
    }

    /** Stamps adopted from a restored backup, so restoring doesn't count as an edit. */
    fun adopt(tags: List<BackupTag>): BackupBook {
        val seen2 = LinkedHashMap(seen)
        for (t in tags) if (!t.isRemoved) seen2[t.k0Hex.lowercase()] = fingerprint(t) to (t.updated ?: 0)
        return copy(seen = seen2, removed = removed - tags.filter { !it.isRemoved }.map { it.k0Hex.lowercase() }.toSet())
    }

    /** This device's view as backup entries: live tags with their stamps, then tombstones. */
    fun entries(current: List<BackupTag>): List<BackupTag> =
        current.map { t -> t.copy(k0Hex = t.k0Hex.lowercase(), updated = seen[t.k0Hex.lowercase()]?.second) } +
            removed.map { (k, at) -> BackupTag.tombstone(k, at) }

    companion object {
        /** what a change is measured on: name, type, mark and the settings */
        fun fingerprint(t: BackupTag) = listOf(t.name, t.type, t.mark, t.settings.toString()).joinToString("\u0000")
    }
}

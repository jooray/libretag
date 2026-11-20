package app.libretag.usb

/**
 * Wi-Fi zones of a tag with a Wi-Fi scanner (ESP32 boards, docs/design/fw-wifi-esp32.md,
 * PROTOCOL.md 6.1 `wzone`). A zone is a fingerprint of the networks the tag sees where it sits,
 * so it can only be taken on the spot (`wzone snap`), never drawn on a map. The tag keeps keyed
 * hashes, not BSSIDs: the app only ever sees names, counts and scores.
 */
data class WifiZoneEntry(val index: Int, val name: String, val aps: Int, val scans: Int)

/** One scan of a snap: APs heard, of them usable for zones (globally administered). */
data class SnapStep(val k: Int, val aps: Int, val usable: Int)

/** `wzone snap`: the stored zone, or why not ([error], the firmware's ERR line). */
data class SnapResult(val zone: WifiZoneEntry?, val steps: List<SnapStep>, val error: String?)

/** A `SCORE` line: how much one zone looks like the scan. */
data class ZoneScore(val index: Int, val name: String, val score: Int, val matched: Int, val of: Int)

/** One `wzone test` scan: the tag's own decision ([zone], -1 = none) and every zone's score. */
data class WifiTestScan(val k: Int, val aps: Int, val usable: Int, val zone: Int, val score: Int, val scores: List<ZoneScore>)

/** Match thresholds (`status`: `wifi zones=<n> enter=<e> stay=<s> min_match=<m>`), firmware defaults. */
data class WifiThresholds(val enter: Int = 40, val stay: Int = 30, val minMatch: Int = 2)

/** How a zone compares with what the tag sees now. */
enum class WifiVerdict {
    /** score at least `enter` with enough networks in common: the tag would go quiet here */
    INSIDE,
    /** between `stay` and `enter`: it stays inside if it was, but does not enter */
    WEAK,
    OUTSIDE,
}

object WifiZoneParsers {
    private val WZONE = Regex("""^WZONE (\d+) (\S+) (\d+) AP from (\d+) scan""")
    private val SNAP = Regex("""^SNAP (\d+) (\d+) AP (\d+) for zones""")
    private val OK_SNAP = Regex("""^OK wzone (\d+)""")
    private val TEST = Regex("""^TEST (\d+) (\d+) AP (\d+) for zones: zone (-?\d+) score (\d+)""")
    private val SCORE = Regex("""^SCORE (\d+) (\d+) (\S+) (\d+) matched (\d+) of (\d+)""")
    private val STATUS = Regex("""\bwifi zones=(\d+) enter=(\d+) stay=(\d+) min_match=(\d+)""")

    private fun clean(lines: List<String>) = lines.map { it.trim('\r', '\n', ' ', '\t') }.filter { it.isNotEmpty() }

    /** `wzone list` (and the header lines of `show` / `snap`). */
    fun parseList(lines: List<String>): List<WifiZoneEntry> = clean(lines).mapNotNull { l ->
        WZONE.find(l)?.groupValues?.let { WifiZoneEntry(it[1].toInt(), it[2], it[3].toInt(), it[4].toInt()) }
    }.distinctBy { it.index }

    /** A progress line of `wzone snap`, else null. */
    fun parseSnapStep(line: String): SnapStep? =
        SNAP.find(line.trim())?.groupValues?.let { SnapStep(it[1].toInt(), it[2].toInt(), it[3].toInt()) }

    fun parseSnap(lines: List<String>): SnapResult {
        val l = clean(lines)
        val steps = l.mapNotNull { parseSnapStep(it) }
        val err = l.firstOrNull { it.startsWith("ERR") }
        val slot = l.firstNotNullOfOrNull { OK_SNAP.find(it)?.groupValues?.get(1)?.toInt() }
        val zone = slot?.let { s -> parseList(l).firstOrNull { it.index == s } }
        return SnapResult(zone, steps, if (zone == null) err ?: "ERR no answer" else null)
    }

    /** `wzone test [scans]`: one entry per scan. */
    fun parseTest(lines: List<String>): List<WifiTestScan> {
        val out = ArrayList<WifiTestScan>()
        var cur: WifiTestScan? = null
        for (l in clean(lines)) {
            TEST.find(l)?.groupValues?.let { g ->
                cur?.let { out += it }
                cur = WifiTestScan(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), emptyList())
            }
            SCORE.find(l)?.groupValues?.let { g ->
                val k = g[1].toInt()
                val s = ZoneScore(g[2].toInt(), g[3], g[4].toInt(), g[5].toInt(), g[6].toInt())
                cur?.takeIf { it.k == k }?.let { cur = it.copy(scores = it.scores + s) }
            }
        }
        cur?.let { out += it }
        return out
    }

    /** The thresholds line of `status`; null on firmware without Wi-Fi zones. */
    fun parseThresholds(lines: List<String>): WifiThresholds? = lines.firstNotNullOfOrNull { STATUS.find(it) }?.groupValues
        ?.let { WifiThresholds(it[2].toInt(), it[3].toInt(), it[4].toInt()) }

    /** Number of Wi-Fi zones from `status`; null when the firmware has none (no Wi-Fi scanner). */
    fun parseZoneCount(lines: List<String>): Int? = lines.firstNotNullOfOrNull { STATUS.find(it) }?.groupValues?.get(1)?.toInt()

    /**
     * How zone [s] compares with the scan, by the tag's own rule (`lt_wzone_match`): inside at
     * `enter` with at least `min_match` networks in common (1 for a zone of one network), weak
     * where the tag would only stay inside.
     */
    fun verdict(s: ZoneScore?, thr: WifiThresholds = WifiThresholds()): WifiVerdict {
        if (s == null || s.matched == 0) return WifiVerdict.OUTSIDE
        val need = minOf(thr.minMatch, maxOf(1, s.of))
        return when {
            s.score >= thr.enter && s.matched >= need -> WifiVerdict.INSIDE
            s.score >= thr.stay && s.matched >= need -> WifiVerdict.WEAK
            else -> WifiVerdict.OUTSIDE
        }
    }
}

object WifiZoneCommands {
    const val LIST = "wzone list"
    /** firmware: names of 1..15 bytes without spaces, 1..20 scans */
    const val NAME_MAX_BYTES = 15
    const val SNAP_DEFAULT = 5
    const val SNAP_MAX = 20

    /**
     * A name the console takes: spaces become '-', control characters go, at most 15 bytes of
     * UTF-8 (never cut inside a character). Null when nothing is left.
     */
    fun cleanName(name: String): String? {
        val s = name.trim().replace(Regex("\\s+"), "-").filter { !it.isISOControl() }
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > NAME_MAX_BYTES) break
            out.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return out.toString().ifEmpty { null }
    }

    fun snap(name: String, scans: Int = SNAP_DEFAULT): String {
        val n = requireNotNull(cleanName(name)) { "empty name" }
        return "wzone snap $n ${scans.coerceIn(1, SNAP_MAX)}"
    }

    fun test(scans: Int = 1) = "wzone test ${scans.coerceIn(1, 20)}"

    fun delete(index: Int) = "wzone del $index"
}

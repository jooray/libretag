package app.libretag.data

import java.time.Instant
import java.util.Locale

/**
 * A tag's history as GPX (GPS fixes only, one track) or CSV (every report, with the estimate
 * without GPS where there is one), for the share sheet. Times in UTC (ISO 8601).
 */
object HistoryExport {
    private fun iso(ms: Long): String = if (ms <= 0) "" else Instant.ofEpochMilli(ms).toString()
    private fun num(d: Double, digits: Int = 7) = String.format(Locale.ROOT, "%.${digits}f", d)

    /** when the position was true: the GPS time, else the time the phone received the report */
    private fun whenMs(p: Position) = if (p.fixTime > 0) p.fixTime * 1000 else p.receivedAt

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun gpx(t: Tracker, creator: String): String {
        val fixes = t.positions.filter { it.hasFix }.sortedBy { whenMs(it) }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(xml(creator)).append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><name>").append(xml(t.name)).append("</name></metadata>\n")
        sb.append("  <trk><name>").append(xml(t.name)).append("</name><trkseg>\n")
        for (p in fixes) {
            sb.append("    <trkpt lat=\"").append(num(p.lat)).append("\" lon=\"").append(num(p.lon)).append("\">")
            iso(whenMs(p)).takeIf { it.isNotEmpty() }?.let { sb.append("<time>").append(it).append("</time>") }
            sb.append("</trkpt>\n")
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    const val CSV_HEADER = "n,received_utc,fix_utc,has_fix,lat,lon,accuracy_m,battery_pct,mode,via,hops,quiet,est_lat,est_lon,est_r68_m,est_r95_m"

    fun csv(t: Tracker): String {
        val sb = StringBuilder(CSV_HEADER).append('\n')
        for (p in t.positions.sortedWith(compareBy({ it.receivedAt }, { it.n }))) {
            val est = p.est?.takeIf { it.state == "ok" }
            sb.append(listOf(
                p.n.takeIf { it >= 0 }?.toString() ?: "",
                iso(p.receivedAt),
                if (p.fixTime > 0) iso(p.fixTime * 1000) else "",
                if (p.hasFix) "1" else "0",
                if (p.hasFix) num(p.lat) else "",
                if (p.hasFix) num(p.lon) else "",
                p.haccM?.toString() ?: "",
                p.battery?.toString() ?: "",
                p.mode,
                p.via,
                p.hops?.toString() ?: "",
                if (p.quiet) "1" else "0",
                est?.let { num(it.lat) } ?: "",
                est?.let { num(it.lon) } ?: "",
                est?.let { it.r68.toLong().toString() } ?: "",
                est?.let { it.r95.toLong().toString() } ?: "",
            ).joinToString(",") { csvField(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** a file name without characters that trouble file systems or share targets */
    fun fileName(t: Tracker, ext: String, nowMs: Long): String {
        val base = t.name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trim('-').ifEmpty { "tag" }.take(40)
        return "libretag-$base-${Instant.ofEpochMilli(nowMs).toString().take(10)}.$ext"
    }
}

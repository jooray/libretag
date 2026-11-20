package app.libretag.usb

import app.libretag.proto.Mode
import java.util.Locale

/** `version`: "LibreTag tracker 0.5.2 on ZephCore da3513aa6d08, board wio_tracker_l1". */
data class FirmwareVersion(val version: String, val zephcore: String, val board: String)

/** One `history` line: report counter, firmware HF_* flags, sealed report P (copy j = 2). */
class HistoryRecord(val n: Long, val flags: Int, val p: ByteArray,
    /** 0.6: copy j (2 a sent report, 3 a wake-up without a report) and the record number */
    val j: Int = HISTORY_J, val seq: Long = 0) {
    val fix get() = flags and HF_FIX != 0
    val relayed get() = flags and HF_RELAYED != 0
    val retransmitted get() = flags and HF_RETX != 0
    val dropped get() = flags and HF_DROPPED != 0

    override fun equals(other: Any?) = other is HistoryRecord && other.n == n && other.flags == flags && other.p.contentEquals(p)
    override fun hashCode() = (n.hashCode() * 31 + flags) * 31 + p.contentHashCode()
    override fun toString() = "HistoryRecord(n=$n, flags=$flags, p=${p.size} B)"

    companion object {
        /** histrec flags in main_tracker.cpp */
        const val HF_FIX = 1
        const val HF_RELAYED = 2
        const val HF_RETX = 4
        const val HF_DROPPED = 8
        const val HF_CMD = 16
        const val HF_BORDER = 32
        const val HF_DISCOVER = 64
        const val HF_LOW = 128

        /** the tracker seals its stored copy as j = 2: never on air */
        const val HISTORY_J = 2
    }
}

/** One `log` line: diagnostics of a past report, no position. */
data class LogRecord(
    val n: Long,
    val uptimeS: Long,
    /** wake to report: GPS search (and fix timeout) */
    val searchS: Int,
    /** null when the firmware stored 255 (unknown) */
    val batteryPct: Int?,
    /** raw mode number (Mode ordinal) */
    val mode: Int,
    /** repeaters heard */
    val nbTotal: Int,
    val fix: Boolean,
    val relayed: Boolean,
    val retransmitted: Boolean,
    val dropped: Boolean,
    val command: Boolean,
    val border: Boolean,
    val discover: Boolean,
    val lowBattery: Boolean,
    /** 0.6: record number, copy j (3 = a wake-up without a report) and the quiet words */
    val seq: Long = 0,
    val j: Int = 2,
    val silent: Boolean = false,
    val quiet: Boolean = false,
    val owner: Boolean = false,
    val zone: Boolean = false,
    val epochSkip: Boolean = false,
    val ble: Boolean = false,
) {
    val modeEnum: Mode? get() = Mode.entries.getOrNull(mode)
}

enum class BleAddressKind { PUBLIC, STATIC, RPA, NRPA, UNKNOWN }

/** One `blescan` row. [companyId] is null for "no manufacturer data" (FFFF), [name] null when empty. */
data class BleDevice(
    val address: String,
    val kind: BleAddressKind,
    val rssi: Int,
    val heard: Int,
    val companyId: Int?,
    val name: String?,
    /** 0.6 `D` lines: rssi min/avg (max is [rssi]), the tag's own anchor, payload fingerprint, 16-bit service UUIDs, connectable */
    val rssiMin: Int? = null,
    val rssiAvg: Int? = null,
    val owner: Boolean = false,
    val fp: String? = null,
    val uuids16: List<Int> = emptyList(),
    val connectable: Boolean? = null,
    /** service data uuid (16-bit) and its hex, e.g. ASHA fdf0 */
    val serviceData: Pair<Int, String>? = null,
    val mfgHex: String? = null,
    /** GAP appearance (ap=), null when the device sends none */
    val appearance: Int? = null,
) {
    /** public and static random addresses do not rotate: usable as an anchor */
    val stable: Boolean get() = kind == BleAddressKind.PUBLIC || kind == BleAddressKind.STATIC
}

data class BleScan(val adverts: Int, val deviceCount: Int, val devices: List<BleDevice>)

/** A home zone (SPEC 11.1). [options] are any extra words the firmware prints (e.g. quiet, zerohop). */
data class Zone(val index: Int, val lat: Double, val lon: Double, val radiusM: Int, val options: List<String> = emptyList())

/** A known Bluetooth device (SPEC 11.2): by address, by advertised name, or as the tracker's hash. */
data class AnchorEntry(val index: Int?, val address: String?, val name: String?, val hashHex: String?,
    /** firmware 0.6 `ANCHOR i <kind> …`: addr, name, mfg, svc, irk */
    val kind: String? = null, val irkHex: String? = null)

/** `blepair`: an identity resolving key, optionally with the device's identity address. */
data class IrkEntry(val irkHex: String, val address: String?)

/**
 * Pure parsers for the tracker's USB console output (firmware main_tracker.cpp, PROTOCOL.md 6).
 * Lines may carry \r, the echo of the command and stray log lines; parsers skip what they do not know.
 */
object ConsoleParsers {
    private val VERSION = Regex("""LibreTag tracker (\S+) on ZephCore (\S+), board (\S+)""")
    private val HIST = Regex("""^H (\d+) (\d+) ([0-9a-fA-F]*)$""")
    /** firmware 0.6: H <seq> <n> <j> <flags> <hex P> (PROTOCOL.md 6) */
    private val HIST6 = Regex("""^H (\d+) (\d+) (\d+) (\d+) ([0-9a-fA-F]+)$""")
    /** 0.6: L <seq> <n> <j> <uptime> <search> <batt> <mode> <rep> <words> */
    private val LOG6 = Regex("""^L (\d+) (\d+) (\d+) (\d+) (\d+) (\d+) (\d+) (\d+)(?:\s+(.*))?$""")
    /** 0.6: D <addr> <kind> rssi=min/avg/max n=… [key=value …] [nameC="…"] [OWNER] fp=XXXX */
    /** start of a D line: "D", the address, then key=value fields in any order (PROTOCOL.md 6.1) */
    private val BLE_D = Regex("""^D\s+([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})(?=\s|$)(.*)$""")
    /** the name is raw bytes in quotes (spaces, quotes, '=' inside): it ends at the last quote before OWNER / fp= / the end */
    private val BLE_NAME = Regex("""(?:^|\s)name([CS]?)="(.*)"(?=\s+OWNER\b|\s+fp=|\s*$)""")
    private val BLE_KV = Regex("""(?:^|\s)(\w+)=(\S*)""")
    private val LOG = Regex("""^L (\d+) (\d+) (\d+) (\d+) (\d+) (\d+)(?:\s+(.*))?$""")
    private val BLE_HEAD = Regex("""^blescan: (\d+) advert\(s\) from (\d+) device\(s\)""")
    private val BLE_ROW = Regex("""^([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\s+(\w+)\s+rssi\s+(-?\d+)\s+x(\d+)\s+co\s+([0-9A-Fa-f]{4})(?:\s+(.*))?$""")
    private val MAC = Regex("""(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])""")
    private val REAL_ANCHOR = Regex("""^ANCHOR (\d+) (addr|name|mfg|svc|irk) (.*)$""")
    private val NUM = Regex("""-?\d+(?:\.\d+)?""")
    private val IRK = Regex("""\bIRK\b.*?(?<![0-9A-Fa-f:])([0-9A-Fa-f]{32})(?![0-9A-Fa-f])""")

    private fun clean(lines: List<String>) = lines.map { it.trim('\r', '\n', ' ', '\t') }.filter { it.isNotEmpty() }

    fun parseVersion(lines: List<String>): FirmwareVersion? =
        clean(lines).firstNotNullOfOrNull { VERSION.find(it) }?.let {
            FirmwareVersion(it.groupValues[1], it.groupValues[2], it.groupValues[3].trimEnd(','))
        }

    fun parseVersion(line: String): FirmwareVersion? = parseVersion(listOf(line))

    /**
     * Semantic version order: numeric parts compared as numbers ("0.10.0" > "0.9.9"), a missing
     * part is 0, and a pre-release ("0.6.0-rc1") is older than its release. A leading "v" is ignored.
     */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String?> {
            val s = v.trim().removePrefix("v").substringBefore('+')
            val core = s.substringBefore('-')
            val pre = if ('-' in s) s.substringAfter('-') else null
            return core.split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 } to pre
        }
        val (ca, pa) = split(a)
        val (cb, pb) = split(b)
        for (i in 0 until maxOf(ca.size, cb.size)) {
            val c = ca.getOrElse(i) { 0 }.compareTo(cb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pa == null && pb == null -> 0
            pa == null -> 1
            pb == null -> -1
            else -> comparePre(pa, pb)
        }
    }

    private fun comparePre(a: String, b: String): Int {
        val xa = a.split('.')
        val xb = b.split('.')
        for (i in 0 until maxOf(xa.size, xb.size)) {
            val p = xa.getOrNull(i) ?: return -1
            val q = xb.getOrNull(i) ?: return 1
            val c = if (p.all { it.isDigit() } && q.all { it.isDigit() }) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return c
        }
        return 0
    }

    /** [a] is an older version than [b] (e.g. the tracker's firmware vs the one the app bundles). */
    fun isOlder(a: String, b: String): Boolean = compareVersions(a, b) < 0

    /** `history [from_n]`: "H <n> <flags> <hex>" lines, oldest first, until END. */
    fun parseHistory(lines: List<String>): List<HistoryRecord> {
        val out = mutableListOf<HistoryRecord>()
        for (l in clean(lines)) {
            if (l == "END" || l.startsWith("END ")) break
            val m6 = HIST6.matchEntire(l)
            if (m6 != null) {
                val hx = m6.groupValues[5]
                if (hx.length % 2 != 0) continue
                val p = ByteArray(hx.length / 2) { hx.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
                out += HistoryRecord(m6.groupValues[2].toLong(), m6.groupValues[4].toInt(), p, m6.groupValues[3].toInt(), m6.groupValues[1].toLong())
                continue
            }
            val m = HIST.matchEntire(l) ?: continue
            val hex = m.groupValues[3]
            if (hex.length % 2 != 0) continue
            val p = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            out += HistoryRecord(m.groupValues[1].toLong(), m.groupValues[2].toInt(), p)
        }
        return out
    }

    /** `log [count]`: "L n uptime_s search_s batt mode repeaters flags…" lines until END. */
    fun parseLog(lines: List<String>): List<LogRecord> {
        val out = mutableListOf<LogRecord>()
        for (l in clean(lines)) {
            if (l == "END" || l.startsWith("END ")) break
            val m6 = LOG6.matchEntire(l)
            if (m6 != null) {
                val g = m6.groupValues
                val w = g[9].split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
                val batt = g[6].toInt()
                out += LogRecord(
                    n = g[2].toLong(), uptimeS = g[4].toLong(), searchS = g[5].toInt(),
                    batteryPct = if (batt > 100) null else batt, mode = g[7].toInt(), nbTotal = g[8].toInt(),
                    fix = "fix" in w, relayed = "relayed" in w, retransmitted = "retx" in w,
                    dropped = "DROPPED" in w || "dropped" in w, command = "cmd" in w, border = "border" in w,
                    discover = "discover" in w, lowBattery = "low" in w,
                    seq = g[1].toLong(), j = g[3].toInt(), silent = "silent" in w, quiet = "quiet" in w,
                    owner = "owner" in w, zone = "zone" in w, epochSkip = "epoch-skip" in w, ble = "ble" in w,
                )
                continue
            }
            val m = LOG.matchEntire(l) ?: continue
            val g = m.groupValues
            val words = g[7].split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            val batt = g[4].toInt()
            out += LogRecord(
                n = g[1].toLong(),
                uptimeS = g[2].toLong(),
                searchS = g[3].toInt(),
                batteryPct = if (batt > 100) null else batt,
                mode = g[5].toInt(),
                nbTotal = g[6].toInt(),
                fix = "fix" in words,
                relayed = "relayed" in words,
                retransmitted = "retx" in words,
                dropped = "DROPPED" in words || "dropped" in words,
                command = "cmd" in words,
                border = "border" in words,
                discover = "discover" in words,
                lowBattery = "low" in words,
            )
        }
        return out
    }

    /**
     * `blescan` result: the header, `D` lines, END. Every other line (the echo, OK, log lines,
     * progress) is skipped; a row without a usable address is dropped (empty table slots print as
     * 00:00:00:00:00:00, rssi 0, n=0), and an address that shows up twice is merged. Null when
     * neither the header nor any row came.
     */
    fun parseBleScan(lines: List<String>): BleScan? {
        val cl = clean(lines)
        val head = cl.indexOfFirst { BLE_HEAD.containsMatchIn(it) }
        val body = if (head >= 0) cl.drop(head + 1).takeWhile { it != "END" && !it.startsWith("END ") } else cl
        val rows = body.mapNotNull { parseBleD(it) ?: parseBleRow(it) }
        if (head < 0 && rows.isEmpty()) return null
        val devices = dedupe(rows)
        val hm = if (head >= 0) BLE_HEAD.find(cl[head]) else null
        return BleScan(hm?.groupValues?.get(1)?.toInt() ?: 0, hm?.groupValues?.get(2)?.toInt() ?: devices.size, devices)
    }

    /** One entry per address, in first-seen order: strongest signal, and whatever name and maker any copy had. */
    fun dedupe(rows: List<BleDevice>): List<BleDevice> {
        val by = LinkedHashMap<String, BleDevice>()
        for (d in rows) {
            val o = by[d.address]
            by[d.address] = if (o == null) d else {
                val (a, b) = if (d.rssi > o.rssi) d to o else o to d
                a.copy(name = a.name ?: b.name, companyId = a.companyId ?: b.companyId, appearance = a.appearance ?: b.appearance,
                    owner = a.owner || b.owner, heard = maxOf(a.heard, b.heard), uuids16 = (a.uuids16 + b.uuids16).distinct())
            }
        }
        return by.values.toList()
    }

    /** Order for a list the owner picks from: named devices first, then by the strongest signal. */
    fun forPicking(list: List<BleDevice>): List<BleDevice> =
        list.sortedWith(compareByDescending<BleDevice> { !it.name.isNullOrBlank() }.thenByDescending { it.rssi })

    /** A real device address: not all zeros (an empty slot) and not all ones. */
    fun validAddress(a: String): Boolean {
        val hex = a.replace(":", "")
        return hex.length == 12 && hex.all { it in "0123456789abcdefABCDEF" } && hex.any { it != '0' } && hex.uppercase() != "FFFFFFFFFFFF"
    }

    private fun addrKind(s: String?) = when (s?.lowercase()) {
        "public" -> BleAddressKind.PUBLIC
        "static" -> BleAddressKind.STATIC
        "rpa" -> BleAddressKind.RPA
        "nrpa" -> BleAddressKind.NRPA
        else -> BleAddressKind.UNKNOWN
    }

    /** RSSI the radio can report; 0 and above (or 127, "unknown") is a blank record, not a device. */
    private fun plausibleRssi(r: Int) = r in -127..-1

    /**
     * One row: ` AA:BB:CC:DD:EE:FF static rssi -70 x3 co 004C "name"`. The name is everything
     * between the first and the last double quote, so names with spaces and quotes survive (today
     * the firmware prints the raw bytes, up to 19). Backslash escapes (\" \\ \xHH) are decoded in
     * case a later firmware escapes names.
     */
    fun parseBleRow(line: String): BleDevice? {
        val m = BLE_ROW.matchEntire(line.trim('\r', '\n', ' ', '\t')) ?: return null
        val g = m.groupValues
        if (!validAddress(g[1]) || !plausibleRssi(g[3].toInt())) return null
        val co = g[5].toInt(16)
        val rest = g[6]
        val first = rest.indexOf('"')
        val last = rest.lastIndexOf('"')
        val name = if (first >= 0 && last > first) unescape(rest.substring(first + 1, last)) else rest.trim()
        return BleDevice(g[1].uppercase(), addrKind(g[2]), g[3].toInt(), g[4].toInt(), if (co == 0xFFFF) null else co, cleanName(name))
    }

    /**
     * Firmware 0.6+ `D` line (main_tracker.cpp ble_print_dev):
     * `D <addr> <kind> rssi=min/avg/max n=… [rsp=…] pdu=… conn=… [fl=…] [tx=…] [ap=…] [co=… ml=… m=… mchg=…]
     * [u16=…] [u128=…] [sd=uuid:len:hex] [nameC="…"|nameS="…"] [OWNER] fp=…`.
     * Fields are read by key, so a missing or reordered one does no harm; `rssi=` may also be a
     * single value. Null for anything that is not a D line or has no usable address or signal.
     */
    fun parseBleD(line: String): BleDevice? {
        val m = BLE_D.matchEntire(line.trim('\r', '\n', ' ', '\t')) ?: return null
        val address = m.groupValues[1].uppercase()
        if (!validAddress(address)) return null
        var rest = m.groupValues[2]
        // the name may contain anything (spaces, quotes, key=value lookalikes): cut it out first
        var name: String? = null
        BLE_NAME.find(rest)?.let { nm ->
            name = cleanName(nm.groupValues[2])
            rest = rest.removeRange(nm.range)
        }
        val words = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kind = addrKind(words.firstOrNull()?.takeIf { '=' !in it })
        val kv = BLE_KV.findAll(rest).associate { it.groupValues[1] to it.groupValues[2] }
        val rs = kv["rssi"]?.split('/')?.map { it.toIntOrNull() } ?: emptyList()
        val rssi = (if (rs.size >= 3) rs[2] else rs.lastOrNull()) ?: return null
        if (!plausibleRssi(rssi)) return null
        val heard = kv["n"]?.toIntOrNull()
        if (heard == 0) return null
        val co = kv["co"]?.toIntOrNull(16)
        val ap = kv["ap"]?.toIntOrNull(16)
        val sd = kv["sd"]?.split(':')?.takeIf { it.size == 3 }?.let { (u, _, h) -> u.toIntOrNull(16)?.let { it to h.lowercase() } }
        return BleDevice(
            address = address, kind = kind, rssi = rssi, heard = heard ?: 1,
            companyId = if (co == null || co == 0xFFFF) null else co, name = name,
            rssiMin = rs.getOrNull(0).takeIf { rs.size >= 3 }, rssiAvg = rs.getOrNull(1).takeIf { rs.size >= 3 },
            owner = "OWNER" in words,
            fp = kv["fp"]?.takeIf { it.matches(Regex("[0-9A-Fa-f]{4}")) }?.uppercase(),
            uuids16 = kv["u16"]?.split(',')?.mapNotNull { it.toIntOrNull(16) } ?: emptyList(),
            connectable = kv["conn"]?.let { it == "1" },
            serviceData = sd,
            mfgHex = kv["m"]?.lowercase()?.ifEmpty { null },
            appearance = ap?.takeIf { it != 0 },
        )
    }

    /** A name as shown: control characters (NUL padding, line noise) dropped, blank = none. */
    private fun cleanName(s: String): String? = s.filter { !it.isISOControl() }.trim().ifEmpty { null }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == '"' || n == '\\' -> { b.append(n); i += 2; continue }
                    n == 'x' && i + 3 < s.length && s.substring(i + 2, i + 4).all { it.isLetterOrDigit() } -> {
                        val v = s.substring(i + 2, i + 4).toIntOrNull(16)
                        if (v != null) { b.append(v.toChar()); i += 4; continue }
                    }
                }
            }
            b.append(c)
            i++
        }
        return b.toString()
    }

    /**
     * `zone list`, tolerant until the firmware fixes a format: any line with an optional "zone"
     * word, an optional integer index, then lat, lon and radius in metres, e.g.
     * "zone 0 48.70120 21.25410 150", "0: 48.70120,21.25410 r=150", "48.7012 21.2541 150m quiet".
     */
    fun parseZones(lines: List<String>): List<Zone> {
        val out = mutableListOf<Zone>()
        for (l in clean(lines)) {
            if (l.startsWith("END") || l.startsWith("OK") || l.startsWith("ERR")) continue
            val body = l.removePrefix("zone").removePrefix("ZONE").trim()
            if (body.isEmpty() || !(body[0].isDigit() || body[0] == '-')) continue
            val nums = NUM.findAll(body).map { it.value }.toList()
            val (idx, rest) = when {
                nums.size >= 4 && '.' !in nums[0] && '.' in nums[1] -> nums[0].toInt() to nums.subList(1, 4)
                nums.size >= 3 && '.' in nums[0] -> null to nums.subList(0, 3)
                else -> continue
            }
            val lat = rest[0].toDouble()
            val lon = rest[1].toDouble()
            val r = rest[2].toDouble().toInt()
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || r <= 0) continue
            val options = body.replace(NUM, " ").split(Regex("[^A-Za-z_]+"))
                .filter { it.isNotEmpty() && it !in setOf("r", "m", "zone") }
            out += Zone(idx ?: out.size, lat, lon, r, options)
        }
        return out
    }

    /**
     * `anchor list`, tolerant: an optional index, then a MAC address, `name:<name>` (or a quoted
     * name), or a 12-hex-digit hash (the tracker may store HMAC(ble_key, address)[0:6] only).
     */
    fun parseAnchors(lines: List<String>): List<AnchorEntry> {
        val out = mutableListOf<AnchorEntry>()
        for (l in clean(lines)) {
            if (l.startsWith("END") || l.startsWith("OK") || l.startsWith("ERR")) continue
            // firmware 0.6: "ANCHOR 0 addr AA:BB:..", "ANCHOR 1 name Oura ring", "ANCHOR 2 irk <hex32> <identity>"
            val m = REAL_ANCHOR.matchEntire(l)
            if (m != null) {
                val i = m.groupValues[1].toInt(); val k = m.groupValues[2]; val v = m.groupValues[3].trim()
                out += when (k) {
                    "addr" -> AnchorEntry(i, v.uppercase(), null, null, k)
                    "name" -> AnchorEntry(i, null, v, null, k)
                    "irk" -> v.split(' ').let { AnchorEntry(i, it.getOrNull(1)?.uppercase(), null, null, k, it[0].lowercase()) }
                    else -> AnchorEntry(i, null, null, v, k)
                }
                continue
            }
            val body = l.removePrefix("anchor").trim()
            val idx = Regex("""^(\d+)\s*[:.)]?\s+""").find(body)?.let { it.groupValues[1].toInt() }
            val mac = MAC.find(body)?.value?.uppercase()
            val name = Regex("""name:\s*"?(.*?)"?\s*$""").find(body)?.groupValues?.get(1)
                ?: Regex("\"(.*)\"").find(body)?.groupValues?.get(1)
            val hash = if (mac == null && name == null) Regex("""(?<![0-9A-Fa-f])[0-9A-Fa-f]{12}(?![0-9A-Fa-f])""").find(body)?.value?.lowercase() else null
            if (mac == null && name == null && hash == null) continue
            out += AnchorEntry(idx, mac, name, hash)
        }
        return out
    }

    /** `blepair` (placeholder until the firmware has it): every "IRK <32 hex>" line, with an address if present. */
    fun parseIrks(lines: List<String>): List<IrkEntry> = clean(lines).mapNotNull { l ->
        val m = IRK.find(l) ?: return@mapNotNull null
        IrkEntry(m.groupValues[1].lowercase(), MAC.find(l)?.value?.uppercase())
    }

    /** The firmware answered "OK …" (and not "ERR …"). */
    fun isOk(lines: List<String>): Boolean = clean(lines).any { it.startsWith("OK") } && clean(lines).none { it.startsWith("ERR") }
}

/**
 * Command strings for the console. Zone and anchor commands do not exist in the firmware yet
 * (SPEC 11.1, 11.2); their names are guesses kept here so one place changes when it lands.
 */
object ConsoleCommands {
    const val VERSION = "version"
    const val ZONE_LIST = "zone list"
    const val ZONE_CLEAR = "zone clear"
    const val ANCHOR_LIST = "anchor list"
    const val ANCHOR_CLEAR = "anchor clear"
    const val BLE_PAIR = "blepair"

    /** IDLE interval in minutes */
    fun interval(min: Int) = "interval ${min.coerceIn(1, 1440)}"

    /** 0.7: listening at checks, 0..180 s since 0.7.2 (0 = off; older firmware clamps to 60) */
    fun check(s: Int) = "check ${s.coerceIn(0, 180)}"

    /** 0.7.3: report at every n-th wake-up, listen for commands at the others (1 = every wake-up) */
    fun reportEvery(n: Int) = "report every ${n.coerceIn(1, 24)}"

    /** 0.7: parked quiet after [after] reports in one place, heartbeat [hbH] hours; 0 = off */
    fun still(after: Int, hbH: Int) = if (after <= 0) "still off" else "still $after $hbH"

    fun history(fromN: Long? = null) = if (fromN == null) "history" else "history $fromN"

    fun log(count: Int? = null) = if (count == null) "log" else "log $count"

    fun bleScan(seconds: Int = 20, passive: Boolean = false) =
        "blescan ${seconds.coerceIn(2, 600)}" + if (passive) " passive" else ""

    /** "zone add 48.70120 21.25410 150": 5 decimals (~1 m), always with a dot. */
    fun zoneAdd(lat: Double, lon: Double, rM: Int): String {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "bad coordinates" }
        require(rM in 1..65_535) { "bad radius" }
        return String.format(Locale.ROOT, "zone add %.5f %.5f %d", lat, lon, rM)
    }

    /** By address (firmware 0.6): "anchor add addr AA:BB:CC:DD:EE:FF". */
    fun anchorAddAddress(address: String): String {
        val a = address.trim().uppercase()
        require(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(a)) { "bad address" }
        return "anchor add addr $a"
    }

    /** By advertised name: "anchor add name <text>" (the console joins words with single spaces). */
    fun anchorAddName(name: String): String {
        val n = name.trim().split(Regex("\\s+")).joinToString(" ")
        require(n.isNotEmpty() && n.length <= 30 && '"' !in n) { "bad name" }
        return "anchor add name $n"
    }

    /** A phone's IRK (from `blepair`): "anchor add irk <hex32> [identity address]". */
    fun anchorAddIrk(irkHex: String, identity: String?): String =
        "anchor add irk ${irkHex.lowercase()}" + (identity?.let { " " + it.uppercase() } ?: "")
}

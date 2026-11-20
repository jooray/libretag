package app.libretag.usb

import java.io.Closeable

/** What `status` printed. */
data class TrackerStatus(
    /** K0, printed only while the tracker is not locked (PROTOCOL.md 6) */
    val keyHex: String?,
    val idHex: String?,
    val locked: Boolean,
    val npub: String?,
    val owner: String?,
    val battery: Int?,
    val lastFix: String?,
    val radio: String?,
    val network: String?,
    val transport: String?,
    val intervalMin: Int?,
    // settings the firmware prints (0.7+: check/window/still/heartbeats, 0.7.3+: report_every); null when absent
    val listenS: Int? = null,
    val checkS: Int? = null,
    val windowS: Int? = null,
    val reportEvery: Int? = null,
    /** parked quiet after this many reports in one place, 0 = off */
    val stillAfter: Int? = null,
    val stillHbH: Int? = null,
    val zoneHbH: Int? = null,
    val ownerHbH: Int? = null,
    /** Wi-Fi zones on the tag (`wifi zones=<n> …`), null when the firmware has no Wi-Fi scanner */
    val wifiZones: Int? = null,
    /** the Wi-Fi zone match thresholds the tag uses (same line) */
    val wifiThr: WifiThresholds? = null,
    // 0.12: the `txcap` line (PROTOCOL.md 6) and the counter, for the diagnosis (Tracker.health)
    /** the next report counter (`n=`) */
    val n: Long? = null,
    /** boots in a row without a completed transmitting wake-up */
    val resets: Int? = null,
    /** pin, power, soft, debug, brownout, watchdog, crash, none (0.9.1+) */
    val lastReset: String? = null,
    val bootDelayS: Int? = null,
    /** tx=ok (true), tx=low_voltage (false) */
    val txOk: Boolean? = null,
) {
    companion object {
        private val KEY = Regex("""LIBRETAG KEY ([0-9a-fA-F]{64})""")
        private val ID = Regex("""LIBRETAG ID ([0-9a-fA-F]{16})""")
        private val LOCKED = Regex("""^locked""")
        private val NPUB = Regex("""NOSTR NPUB (npub1[0-9a-z]+)""")
        private val OWNER = Regex("""^owner (npub1[0-9a-z]+)""")
        private val BATT = Regex("""battery (\d+)%""")
        private val FIX = Regex("""last_fix=yes (-?[\d.]+),(-?[\d.]+)""")
        private val RADIO = Regex("""^radio (.+)$""")
        private val NETWORK = Regex("""^network (.+)$""")
        private val TRANSPORT = Regex("""transport=(\w+)""")
        private val INTERVAL = Regex("""interval=(\d+)min""")
        private val LISTEN = Regex("""\blisten=(\d+)s""")
        private val CHECK = Regex("""\bcheck=(\d+)s""")
        private val WINDOW = Regex("""\bwindow=(\d+)s""")
        private val REPORT_EVERY = Regex("""\breport_every=(\d+)""")
        private val STILL = Regex("""(?:^|\s)still=(\d+|off)\b""")
        private val STILL_HB = Regex("""\bstill_hb=(\d+)h""")
        private val ZONE_HB = Regex("""\bzone_hb=(\d+)h""")
        private val OWNER_HB = Regex("""\bowner_hb=(\d+)h""")
        private val N = Regex("""^n=(\d+)\b""")
        private val RESETS = Regex("""\bresets=(\d+)""")
        private val LAST_RESET = Regex("""\blast_reset=([a-z]+)""")
        private val BOOT_DELAY = Regex("""\bboot_delay=(\d+)s""")
        private val TX = Regex("""\btx=(ok|low_voltage)\b""")

        fun parse(lines: List<String>): TrackerStatus {
            fun find(r: Regex, g: Int = 1) = lines.firstNotNullOfOrNull { r.find(it.trim())?.groupValues?.get(g) }
            val fix = lines.firstNotNullOfOrNull { FIX.find(it) }?.let { "${it.groupValues[1]}, ${it.groupValues[2]}" }
            return TrackerStatus(
                keyHex = find(KEY)?.lowercase(),
                idHex = find(ID)?.lowercase(),
                locked = lines.any { LOCKED.containsMatchIn(it.trim()) },
                npub = find(NPUB),
                owner = find(OWNER),
                battery = find(BATT)?.toIntOrNull(),
                lastFix = fix,
                radio = find(RADIO),
                network = find(NETWORK),
                transport = find(TRANSPORT),
                intervalMin = find(INTERVAL)?.toIntOrNull(),
                listenS = find(LISTEN)?.toIntOrNull(),
                checkS = find(CHECK)?.toIntOrNull(),
                windowS = find(WINDOW)?.toIntOrNull(),
                reportEvery = find(REPORT_EVERY)?.toIntOrNull(),
                stillAfter = find(STILL)?.let { if (it == "off") 0 else it.toIntOrNull() },
                stillHbH = find(STILL_HB)?.toIntOrNull(),
                zoneHbH = find(ZONE_HB)?.toIntOrNull(),
                ownerHbH = find(OWNER_HB)?.toIntOrNull(),
                wifiZones = WifiZoneParsers.parseZoneCount(lines),
                wifiThr = WifiZoneParsers.parseThresholds(lines),
                n = find(N)?.toLongOrNull(),
                resets = find(RESETS)?.toIntOrNull(),
                lastReset = find(LAST_RESET),
                bootDelayS = find(BOOT_DELAY)?.toIntOrNull(),
                txOk = find(TX)?.let { it == "ok" },
            )
        }

        private val QUIET = Regex("""^QUIET (none|zone|owner|parked)\b""")

        /** The `quiet` command's answer: "none", "zone", "owner" or "parked"; null when it did not answer. */
        fun parseQuiet(lines: List<String>): String? = lines.firstNotNullOfOrNull { QUIET.find(it.trim())?.groupValues?.get(1) }

        /** The health the diagnosis keeps ([app.libretag.data.Tracker.health]). */
        fun health(s: TrackerStatus, quiet: String?, at: Long) = app.libretag.data.TagHealth(
            at = at, n = s.n, resets = s.resets, lastReset = s.lastReset, bootDelayS = s.bootDelayS, txOk = s.txOk,
            quiet = quiet, intervalMin = s.intervalMin, reportEvery = s.reportEvery,
        )
    }
}

/**
 * The tracker's text CLI (firmware `help`) over a serial link. Blocking: call from Dispatchers.IO.
 * The firmware echoes what it receives; replies end when the line goes quiet.
 */
class TrackerConsole(private val port: SerialLink) : Closeable {
    fun port(): SerialLink = port

    /** last time anything went over the line (ms) */
    private var lastIo = 0L

    /**
     * ESP32 tags light-sleep between wake-ups, and the line that wakes them is lost (often the next
     * one too). They stay awake 30 s after any console input, so after 20 s of silence the app
     * sends empty lines first. CDC tags never sleep their console.
     */
    private fun wake() {
        if (!port.wakeBeforeWrite || System.currentTimeMillis() - lastIo < WAKE_IDLE_MS) return
        port.write("\r".toByteArray())
        drain(1000)
        port.write("\r".toByteArray())
        drain(300)
        lastIo = System.currentTimeMillis()
    }

    /** Write one command line (waking a sleeping tag first); read the answer with [collect]. */
    fun send(cmd: String) {
        wake()
        drain(50)
        port.write("$cmd\r".toByteArray())
        lastIo = System.currentTimeMillis()
    }

    /**
     * One command and its answer. The firmware echoes the line first; the answer ends [quietMs] after
     * the last byte, but only once something besides the echo came (a state write on an ESP32 tag
     * can take longer than [quietMs] between the echo and the answer). Only the first line equal to
     * [cmd] is the echo: `owner <npub>` answers with exactly the line it was sent, and that answer
     * must stay. When nothing at all came back (not even the echo, so the tag never got the line,
     * e.g. an ESP32 waking from light sleep), the line goes out once more.
     */
    fun command(cmd: String, quietMs: Int = 400, maxMs: Int = 3000): List<String> {
        val raw = exchange(cmd, maxOf(quietMs, port.minQuietMs), maxMs)
        if (raw.isNotEmpty()) return answer(raw, cmd)
        lastIo = 0L // wake a sleeping tag again before the repeat
        return answer(exchange(cmd, maxOf(quietMs, port.minQuietMs), maxMs), cmd)
    }

    /** the answer lines: without the echo on a link that echoes (Bluetooth does not, PROTOCOL.md 6.2) */
    private fun answer(lines: List<String>, cmd: String): List<String> = if (port.echoes) withoutEcho(lines, cmd) else lines

    private fun exchange(cmd: String, quietMs: Int, maxMs: Int): List<String> {
        wake()
        drain(50)
        port.write("$cmd\r".toByteArray())
        lastIo = System.currentTimeMillis()
        val text = StringBuilder()
        val start = System.currentTimeMillis()
        var lastData = start
        while (System.currentTimeMillis() - start < maxMs) {
            val b = port.read(100)
            if (b.isNotEmpty()) {
                text.append(String(b, Charsets.UTF_8))
                lastData = System.currentTimeMillis()
                lastIo = lastData
            } else if (text.isNotEmpty() && System.currentTimeMillis() - lastData >= quietMs &&
                answer(lines(text), cmd).isNotEmpty()) {
                break
            }
        }
        return lines(text)
    }

    private fun lines(text: CharSequence) = text.split('\n').map { it.trim('\r', ' ') }.filter { it.isNotEmpty() }

    /**
     * Read lines for up to [maxMs] (for output that comes later, like `blescan`): stops [quietMs]
     * after [done] matched a line. [onLine] sees lines as they come.
     */
    fun collect(maxMs: Int, quietMs: Int = 600, onLine: (String) -> Unit = {}, done: (String) -> Boolean): List<String> {
        val lines = mutableListOf<String>()
        val buf = StringBuilder()
        val start = System.currentTimeMillis()
        var doneAt = 0L
        while (System.currentTimeMillis() - start < maxMs) {
            val b = port.read(100)
            if (b.isNotEmpty()) {
                buf.append(String(b, Charsets.UTF_8))
                lastIo = System.currentTimeMillis()
                var nl = buf.indexOf("\n")
                while (nl >= 0) {
                    val l = buf.substring(0, nl).trim('\r', ' ')
                    buf.delete(0, nl + 1)
                    if (l.isNotEmpty()) { lines += l; onLine(l); if (done(l)) doneAt = System.currentTimeMillis() }
                    nl = buf.indexOf("\n")
                }
            } else if (doneAt > 0 && System.currentTimeMillis() - doneAt >= maxOf(quietMs, port.minQuietMs)) break
        }
        buf.toString().trim('\r', ' ', '\n').takeIf { it.isNotEmpty() }?.let { lines += it }
        return lines
    }

    fun status(): TrackerStatus = TrackerStatus.parse(command("status"))

    /** Unlock a locked console for this connection with the tracker's usb_key (PROTOCOL.md 6). */
    fun unlock(usbKey: ByteArray): Boolean {
        val ch = command("challenge").firstNotNullOfOrNull { Regex("""CHALLENGE ([0-9a-f]{32})""").find(it)?.groupValues?.get(1) }
            ?: return false
        val resp = app.libretag.proto.LibreTag.usbAuth(usbKey, app.libretag.proto.LibreTag.hexToBytes(ch)!!)
        return command("auth " + app.libretag.proto.LibreTag.bytesToHex(resp)).any { it.startsWith("OK unlocked") }
    }

    /** Runs a setting command; true if the firmware answered OK. */
    fun set(cmd: String): Boolean = command(cmd).any { it.startsWith("OK") }

    private fun drain(ms: Int) {
        while (port.read(ms).isNotEmpty()) { /* old log lines */ }
    }

    override fun close() = port.close()

    companion object {
        const val WAKE_IDLE_MS = 20_000L

        /** [lines] without the echo of [cmd]: the first line equal to it, not every one. */
        fun withoutEcho(lines: List<String>, cmd: String): List<String> {
            val i = lines.indexOf(cmd)
            return if (i < 0) lines else lines.subList(0, i) + lines.subList(i + 1, lines.size)
        }
    }
}

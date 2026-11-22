package app.libretag.keys

import app.libretag.data.Tracker
import app.libretag.proto.Keys
import app.libretag.proto.LibreTag

/**
 * The epoch keys of one tag. The owner derives them from K0; someone the owner shared the tag with
 * holds `V_e` (and for "view and control" `C_e`) from a given epoch on and ratchets forward
 * (PROTOCOL.md 1, "read-only sharing"). Epochs before [fromEpoch] can't be read.
 */
interface KeyChain {
    val fromEpoch: Long
    fun view(epoch: Long): ByteArray?
    fun cmd(epoch: Long): ByteArray?
    val canCommand: Boolean
}

class OwnerChain(val keys: Keys) : KeyChain {
    override val fromEpoch = 0L
    override fun view(epoch: Long): ByteArray = keys.view(epoch)
    override fun cmd(epoch: Long): ByteArray = keys.cmd(epoch)
    override val canCommand = true
}

class SharedChain(override val fromEpoch: Long, viewE: ByteArray, cmdE: ByteArray?) : KeyChain {
    private val views = HashMap<Long, ByteArray>().apply { put(fromEpoch, viewE) }
    private val cmds = HashMap<Long, ByteArray>().apply { cmdE?.let { put(fromEpoch, it) } }
    override val canCommand = cmdE != null

    @Synchronized override fun view(epoch: Long): ByteArray? = step(views, epoch)
    @Synchronized override fun cmd(epoch: Long): ByteArray? = step(cmds, epoch)

    private fun step(cache: HashMap<Long, ByteArray>, epoch: Long): ByteArray? {
        if (epoch < fromEpoch || cache.isEmpty()) return null
        cache[epoch]?.let { return it }
        val from = cache.keys.filter { it < epoch }.maxOrNull() ?: return null
        var k = cache[from]!!
        var e = from
        while (e < epoch) {
            k = LibreTag.hmac(k, "LT2-next".toByteArray())
            e++
        }
        cache[epoch] = k
        return k
    }

    companion object {
        fun of(t: Tracker): SharedChain? {
            val v = LibreTag.hexToBytes(t.sharedViewHex)?.takeIf { it.size == 32 } ?: return null
            val c = LibreTag.hexToBytes(t.sharedCmdHex)?.takeIf { it.size == 32 }
            return SharedChain(t.sharedEpoch, v, if (t.sharedControl) c else null)
        }
    }
}

/**
 * Which report counters to try for a tag (same rules as ReportWindow in LibreTag.kt, over any
 * [KeyChain]): a wide window ahead, a short one behind, both copies j = 0, 1; [seen] counters
 * are never reopened. The history copy j = 2 is opened directly by [openHistory].
 */
class TagWindow(private val chain: KeyChain, var lastN: Long, seen: Collection<Long> = emptyList()) {
    private val seen = java.util.TreeSet(seen)
    private var table: Map<String, Pair<Long, Int>> = build()

    private fun firstN(): Long = chain.fromEpoch * LibreTag.EPOCH_REPORTS

    private fun build(): Map<String, Pair<Long, Int>> {
        val from = if (lastN < 0) firstN() else maxOf(firstN(), lastN - BACK)
        val to = if (lastN < 0) firstN() + FIRST_SYNC else lastN + 1 + AHEAD
        val m = HashMap<String, Pair<Long, Int>>()
        for (n in from until to) {
            if (n in seen) continue
            val v = chain.view(LibreTag.epoch(n)) ?: continue
            for (j in 0..1) m[LibreTag.bytesToHex(LibreTag.reportTag(v, n, j))] = n to j
        }
        return m
    }

    @Synchronized
    fun tagsAhead(count: Int): List<String> = table.entries.filter { it.value.first > lastN }
        .sortedBy { it.value.first }.take(2 * count).map { it.key }

    /** The packet's tag is in the window (cheap; [tryOpen] still checks the MAC). */
    @Synchronized
    fun knows(p: ByteArray): Boolean = p.size >= LibreTag.TAG_LEN && table.containsKey(LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN)))

    /**
     * [keep]: the copy came with only an approximate time (0.8.1, `approx`), so n stays open for an
     * exact copy of the same report that may still come from another bridge or our own radio.
     */
    @Synchronized
    fun tryOpen(p: ByteArray, keep: Boolean = false): Triple<Long, Int, app.libretag.proto.Report>? {
        if (p.size < LibreTag.TAG_LEN) return null
        val (n, j) = table[LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN))] ?: return null
        val v = chain.view(LibreTag.epoch(n)) ?: return null
        val r = LibreTag.openReport(v, n, j, p) ?: return null
        if (!keep) seen.add(n)
        if (n > lastN) {
            lastN = n
            while (seen.isNotEmpty() && seen.first() < lastN - BACK) seen.pollFirst()
            table = build()
        } else if (!keep) {
            table = table.filterValues { it.first != n }
        }
        return Triple(n, j, r)
    }

    /** BLE and Wi-Fi packets follow a report within seconds: only near the newest report. */
    @Synchronized
    fun tryOpenExtra(p: ByteArray): Triple<Long, Int, Any>? {
        if (p.size < LibreTag.TAG_LEN || lastN < 0) return null
        val tag = LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN))
        for (n in maxOf(0L, lastN - 8)..lastN + 2) {
            val v = chain.view(LibreTag.epoch(n)) ?: continue
            for (j in LibreTag.J_WIFI until LibreTag.J_BLE + LibreTag.BLE_MAX_PACKETS) {
                if (LibreTag.bytesToHex(LibreTag.reportTag(v, n, j)) != tag) continue
                val x = LibreTag.openExtra(v, n, j, p) ?: return null
                return Triple(n, j, x)
            }
        }
        return null
    }

    /**
     * Resync (0.8.0): the tag is past the window (a week of Lost without the app, restarts that
     * burned counters). Walk forward from the window's end in blocks of [block] counters, up to
     * [cap] past [lastN], and return the first (n, j) whose tag [has] knows (reports seen but not
     * opened): (n, j, tag hex). The caller then opens it with [openAt].
     */
    fun searchForward(has: (String) -> Boolean, block: Long = AHEAD, cap: Long = RESYNC_CAP): Triple<Long, Int, String>? {
        val base = if (lastN < 0) chain.fromEpoch * LibreTag.EPOCH_REPORTS + FIRST_SYNC else lastN + 1 + AHEAD
        val end = maxOf(lastN, chain.fromEpoch * LibreTag.EPOCH_REPORTS - 1) + 1 + cap
        var from = base
        while (from < end) {
            val to = minOf(end, from + block)
            for (n in from until to) {
                val v = chain.view(LibreTag.epoch(n)) ?: continue
                for (j in 0..1) {
                    val x = LibreTag.bytesToHex(LibreTag.reportTag(v, n, j))
                    if (has(x)) return Triple(n, j, x)
                }
            }
            from = to
        }
        return null
    }

    /** Tags of one forward block (for a relay query when the app runs the window query). */
    fun blockTags(from: Long, count: Long): List<String> = (from until from + count).flatMap { n ->
        val v = chain.view(LibreTag.epoch(n)) ?: return@flatMap emptyList()
        (0..1).map { j -> LibreTag.bytesToHex(LibreTag.reportTag(v, n, j)) }
    }

    /** Open [p] as report n copy j found by [searchForward]; moves the window there. */
    @Synchronized
    fun openAt(p: ByteArray, n: Long, j: Int, keep: Boolean = false): app.libretag.proto.Report? {
        val v = chain.view(LibreTag.epoch(n)) ?: return null
        val r = LibreTag.openReport(v, n, j, p) ?: return null
        if (!keep) seen.add(n)
        if (n > lastN) { lastN = n; table = build() }
        return r
    }

    /** A record from the tag's own history (sealed as copy j = 2, never on air). */
    fun openHistory(n: Long, p: ByteArray, j: Int? = null): app.libretag.proto.Report? {
        val v = chain.view(LibreTag.epoch(n)) ?: return null
        if (j != null) return LibreTag.openReport(v, n, j, p)
        // j = 2: a report as sent; j = 3: a fix stored during a quiet stretch, never sent
        return LibreTag.openReport(v, n, LibreTag.J_HIST, p) ?: LibreTag.openReport(v, n, LibreTag.J_HIST_SILENT, p)
    }

    companion object {
        const val AHEAD = 2048L
        const val BACK = 256L
        const val FIRST_SYNC = 4096L
        /** resync searches at most this far past the last report */
        const val RESYNC_CAP = 65_536L
        const val HISTORY_J = 2
    }
}

/**
 * What opens a tag's console (PROTOCOL.md 6 and 6.2): its `id` and `usb_key`. The owner derives them
 * from K0; a full-access share carries them in the link (`uk`, `id`), without K0.
 */
class ConsoleKey(val id: ByteArray, val usbKey: ByteArray) {
    val idHex: String get() = LibreTag.bytesToHex(id)

    companion object {
        fun of(k: Keys) = ConsoleKey(k.id, k.usbKey)

        /** the console key a full-access share brought, null for any other tag */
        fun shared(t: Tracker): ConsoleKey? {
            if (!t.sharedAdmin) return null
            val u = LibreTag.hexToBytes(t.sharedUsbHex)?.takeIf { it.size == 32 } ?: return null
            val i = LibreTag.hexToBytes(t.sharedIdHex)?.takeIf { it.size == 8 } ?: return null
            return ConsoleKey(i, u)
        }
    }
}

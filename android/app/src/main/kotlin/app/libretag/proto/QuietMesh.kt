package app.libretag.proto

import java.security.MessageDigest
import java.util.Base64

/**
 * Quiet mesh (LT2, firmware 0.10.0, app 0.14.0): the wire-level parts the app needs. Mirrors
 * host/src/libretag/quietmesh.py; tests check it against docs/vectors/quiet-mesh-v1.json.
 * PROTOCOL.md 2.6 (anchor and slots), 3.3 (ACK), 4.3 (ACK entries); SPEC.md 3.2.
 */
object QuietMesh {
    /** copies on air: 0 home flood, 1 border flood, 9 zero-hop hello, 10 wider flood, 11 along a path */
    const val J_NEAR = 9
    const val J_WIDE = 10
    const val J_ROUTED = 11
    val J_ON_AIR = listOf(LibreTag.J_HOME, LibreTag.J_BORDER, J_NEAR, J_WIDE, J_ROUTED)
    val J_FLOODS = setOf(LibreTag.J_HOME, LibreTag.J_BORDER, J_WIDE)

    const val ACK_LEN = 13
    const val ACK_INFO_HOLD = 0x0F
    const val ACK_INFO_MORE = 0x10
    const val ACK_INFO_ROUTED = 0x20
    const val ACK_INFO_RESERVED = 0xC0
    const val HOLD_UNIT_S = 3 * 3600L
    /** 12 h; the app offers 0, 2, 4, 8 (0, 6, 12, 24 h) */
    const val HOLD_DEFAULT = 4
    val HOLD_CHOICES = listOf(0, 2, 4, 8)
    const val HOLD_MIN_EXTRA_S = 300L
    const val PATH_MAX_HOPS = 2
    const val MOVE_M = 300.0
    const val SLOT_SEND_AFTER_S = 1L
    const val SLOT_GUARD_BASE_MS = 2000L
    const val MAX_CANDIDATES = 2
    /** a report older than this by its radio time gets a timed ACK instead of a live one */
    const val LIVE_ACK_MAX_AGE_MS = 40_000L
    const val TIMED_ACK_SLOTS_NEAR = 2
    const val TIMED_ACK_SLOTS_ROUTED = 1
    /** untimed command entries reach at least this many counters ahead (R34) */
    const val UNTIMED_AHEAD = 10
    /** live ACK entries expire after 10 minutes; timed ones 120 s after their `at` */
    const val ACK_LIVE_TTL_S = 600L
    const val ACK_TIMED_TTL_S = 120L

    // ---------------------------------------------------------------- the ACK (PROTOCOL.md 3.3)

    private fun ck(n: Long, k: Int): ByteArray = LibreTag.u32(n) + LibreTag.u16(k)

    fun ackKey(cmdE: ByteArray, n: Long, k: Int): ByteArray = LibreTag.hmac(cmdE, "LT2-a".toByteArray(), ck(n, k))

    /** xa: the 4 B the tag compares before any other work. */
    fun ackTag(cmdE: ByteArray, n: Long, k: Int): ByteArray =
        LibreTag.hmac(cmdE, "LT2-ax".toByteArray(), ck(n, k)).copyOf(LibreTag.TAG_LEN)

    fun ackInfo(hold: Int, more: Boolean = false, routed: Boolean = false): Int {
        require(hold in 0..ACK_INFO_HOLD) { "hold is 0..15 (units of 3 h)" }
        return hold or (if (more) ACK_INFO_MORE else 0) or (if (routed) ACK_INFO_ROUTED else 0)
    }

    /** Pa(n, k) = xa || ct || mac (13 B). [info] overrides the field (vectors with reserved bits). */
    fun ackSeal(cmdE: ByteArray, n: Long, k: Int, hold: Int = HOLD_DEFAULT, more: Boolean = false,
                routed: Boolean = false, info: Int? = null): ByteArray {
        val ka = ackKey(cmdE, n, k)
        val xa = ackTag(cmdE, n, k)
        val i = info?.and(0xFF) ?: ackInfo(hold, more, routed)
        val ct = byteArrayOf((i xor (ka[0].toInt() and 0xFF)).toByte())
        val mac = LibreTag.hmac(ka.copyOfRange(16, 32), xa, ct).copyOf(8)
        return xa + ct + mac
    }

    data class Ack(val hold: Int, val more: Boolean, val routed: Boolean, val hashSize: Int = 0, val path: List<String> = emptyList())

    /** Open a 13 B Pa for (n, k): tag, mac, reserved bits. */
    fun ackOpen13(cmdE: ByteArray, n: Long, k: Int, pa: ByteArray): Ack? {
        if (pa.size != ACK_LEN || !MessageDigest.isEqual(pa.copyOf(LibreTag.TAG_LEN), ackTag(cmdE, n, k))) return null
        val ka = ackKey(cmdE, n, k)
        if (!MessageDigest.isEqual(LibreTag.hmac(ka.copyOfRange(16, 32), pa.copyOf(5)).copyOf(8), pa.copyOfRange(5, 13))) return null
        val i = (pa[4].toInt() and 0xFF) xor (ka[0].toInt() and 0xFF)
        if (i and ACK_INFO_RESERVED != 0) return null
        return Ack(i and ACK_INFO_HOLD, i and ACK_INFO_MORE != 0, i and ACK_INFO_ROUTED != 0)
    }

    /**
     * The tag's check of an ACK datagram (channel content). Near form: exactly 13 B, ROUTED 0.
     * Routed form: ROUTED 1, then pl and 1 or 2 entries of 1..3 B, nothing after. The app never
     * receives an ACK; this is here for the vectors and the bench.
     */
    fun ackOpen(cmdE: ByteArray, n: Long, k: Int, dg: ByteArray): Ack? {
        if (dg.size < ACK_LEN) return null
        val a = ackOpen13(cmdE, n, k, dg.copyOf(ACK_LEN)) ?: return null
        val rest = dg.copyOfRange(ACK_LEN, dg.size)
        if (!a.routed) return if (rest.isEmpty()) a else null
        if (rest.isEmpty()) return null
        val hs = ((rest[0].toInt() and 0xFF) shr 6) + 1
        val count = rest[0].toInt() and 0x3F
        if (hs !in 1..3 || count !in 1..PATH_MAX_HOPS) return null
        if (rest.size != 1 + hs * count) return null
        val path = (0 until count).map { LibreTag.bytesToHex(rest.copyOfRange(1 + it * hs, 1 + (it + 1) * hs)) }
        return a.copy(hashSize = hs, path = path)
    }

    /** ACK entry forms (PROTOCOL.md 4.3): 13 B near, 26 B near || routed, 13 B routed. */
    enum class Form(val wire: String) { NEAR("near"), BOTH("both"), ROUTED("routed") }

    fun ackEntryBytes(cmdE: ByteArray, n: Long, k: Int, form: Form, hold: Int = HOLD_DEFAULT, more: Boolean = false): ByteArray {
        val near = ackSeal(cmdE, n, k, hold, more, routed = false)
        val routed = ackSeal(cmdE, n, k, hold, more, routed = true)
        return when (form) { Form.NEAR -> near; Form.BOTH -> near + routed; Form.ROUTED -> routed }
    }

    fun ackEntryContent(cmdE: ByteArray, n: Long, k: Int, form: Form, hold: Int = HOLD_DEFAULT, more: Boolean = false): String =
        Base64.getEncoder().encodeToString(ackEntryBytes(cmdE, n, k, form, hold, more))

    /** The route tag of an ACK entry in [form]: near for the near form, direct for the other two. */
    fun ackRoute(form: Form): String = if (form == Form.NEAR) "near" else "direct"

    /**
     * Which form the app sends for copy [j] (SPEC.md 3.2 "ACK sender"): copy 9 near; a flood copy
     * both forms unless the fix moved more than MOVE_M from the previous report's ([standing]
     * false), then near only; copy 11 routed.
     */
    fun formFor(j: Int, standing: Boolean): Form = when {
        j == J_ROUTED -> Form.ROUTED
        j in J_FLOODS && standing -> Form.BOTH
        else -> Form.NEAR
    }

    /**
     * How long a NEAR tag may go without the next ACK before a report has to arrive (s). Null for
     * hold 0 (every report has to arrive). Never less than one report period plus 5 minutes.
     */
    fun holdS(hold: Int, periodS: Long): Long? = if (hold == 0) null else maxOf(hold * HOLD_UNIT_S, periodS + HOLD_MIN_EXTRA_S)

    /** MOVE_M or 3 x the accuracy, whichever is larger (the tag's `moved` and the app's guard). */
    fun moveM(haccM: Int?): Double = maxOf(MOVE_M, 3.0 * (haccM ?: 0))

    // ---------------------------------------------------------------- slots (PROTOCOL.md 2.6)

    /** ks = HMAC(C_e(n_a), "LT2-slot" || u32(n_a)). */
    fun slotKey(cmdE: ByteArray, nA: Long): ByteArray = LibreTag.hmac(cmdE, "LT2-slot".toByteArray(), LibreTag.u32(nA))

    private fun slotKd(ks: ByteArray, k: Int) = LibreTag.hmac(ks, "LT2-c".toByteArray(), LibreTag.u16(k))

    fun slotCommandTag(ks: ByteArray, k: Int): ByteArray =
        LibreTag.hmac(ks, "LT2-cx".toByteArray(), LibreTag.u16(k)).copyOf(LibreTag.TAG_LEN)

    /** A command for slot k >= 1 of the anchor's grid (k = 0 keeps [LibreTag.sealCommand] with C_e(n)). */
    fun sealSlotCommand(ks: ByteArray, k: Int, cmd: Command): ByteArray {
        require(k in 1..0xFFFF) { "slot k >= 1" }
        return LibreTag.sealWith(slotKd(ks, k), slotCommandTag(ks, k), cmd.encode())
    }

    fun openSlotCommand(ks: ByteArray, k: Int, pc: ByteArray): Command? {
        if (pc.size != LibreTag.CMD_P_LEN || !MessageDigest.isEqual(pc.copyOf(LibreTag.TAG_LEN), slotCommandTag(ks, k))) return null
        return LibreTag.openWith(slotKd(ks, k), pc)?.let { Command.decode(it) }
    }

    fun slotJitter(ks: ByteArray, i: Int, baseS: Long): Long {
        val span = baseS / 5
        if (span <= 0) return 0
        val h = LibreTag.hmac(ks, "LT2-w".toByteArray(), LibreTag.u16(i))
        val v = (h[0].toInt() and 0xFF) or ((h[1].toInt() and 0xFF) shl 8)
        return (v % span) - span / 2
    }

    /** t_k - t0 (s) for k = 1..count on the anchor's grid. */
    fun slotOffsets(ks: ByteArray, baseS: Long, count: Int): List<Long> {
        val out = ArrayList<Long>(count)
        var t = 0L
        for (k in 1..count) {
            t += baseS + slotJitter(ks, k, baseS)
            out += t
        }
        return out
    }

    /** g_k = 2 s + (t_k - t0) x 100 ppm, ms. */
    fun slotGuardMs(offsetS: Long): Long = SLOT_GUARD_BASE_MS + offsetS / 10

    /** Receiver on, relative to t_k in ms: from -(g_k + early) to window + g_k. */
    fun slotWindowMs(offsetS: Long, windowS: Long, earlyS: Long = 0): Pair<Long, Long> {
        val g = slotGuardMs(offsetS)
        return -(g + earlyS * 1000) to windowS * 1000 + g
    }

    /** `at` of a timed entry, unix s: t_k + 1 s. */
    fun slotSendAt(t0S: Long, offsetS: Long): Long = t0S + offsetS + SLOT_SEND_AFTER_S

    /**
     * Fallback flag per send of one command over several grids: only the latest slot keeps the
     * flood fallback, except Lost and Track, where every slot keeps it (R3, R35).
     */
    fun pairFallback(slotTimes: List<Long>, lostOrTrack: Boolean): List<Boolean> {
        if (lostOrTrack || slotTimes.isEmpty()) return List(slotTimes.size) { true }
        val last = slotTimes.max()
        return slotTimes.map { it == last }
    }

    /**
     * Which slots of a grid get a timed command entry: the next 3, then every 3rd, until [untilS]
     * (s after t0); LOST every slot for its first 6 h. [fromK]: the first slot still ahead.
     */
    fun commandSlots(offsets: List<Long>, fromK: Int, untilS: Long, lost: Boolean, firstAtS: Long? = null, max: Int = 12): List<Int> {
        val out = mutableListOf<Int>()
        val start = firstAtS ?: offsets.getOrNull(fromK - 1) ?: return out
        var i = 0
        for (k in fromK..offsets.size) {
            val off = offsets[k - 1]
            if (off > untilS) break
            val dense = lost && off - start < 6 * 3600L
            if (i < 3 || dense || (i - 2) % 3 == 0) out += k
            i++
            if (out.size >= max) break
        }
        return out
    }

    /** SYNC (flags bit 7, 0.10.0): the tag's anchor at the moment report n was built is report n - 1. */
    const val F_SYNC = 0x80

    // ---------------------------------------------------------------- the app's anchor (PROTOCOL.md 2.6, R1, R32)

    /** A grid: the anchor report [n] and its time [t0] (ms). */
    data class Grid(val n: Long, val t0: Long)

    /**
     * The app's view of a tag's anchor: one confirmed grid [a] and at most two candidates, three
     * grids at most. [known] false: "schedule not known" (grids are still used for sends).
     * Immutable; every rule returns the next state. Mirrors quietmesh.AppAnchor.
     */
    data class AppAnchor(
        val a: Grid? = null,
        val cands: List<Grid> = emptyList(),
        val lastN: Long = -1,
        val seen: Map<Long, Long> = emptyMap(),
        val known: Boolean = true,
    ) {
        fun grids(): List<Grid> = listOfNotNull(a) + cands

        /**
         * Report [n], copy [j], exact time [t] (ms; approximate reports are not passed in). [liveAck]:
         * the app sent a live ACK for it; [still]: a fix within MOVE_M of the last one and no BOOT;
         * [dueInGap]: a heartbeat or hold flood fell due between the last report and this one.
         */
        fun onReport(n: Long, j: Int, t: Long, sync: Boolean, liveAck: Boolean = false, still: Boolean = true,
                     dueInGap: Boolean = false): AppAnchor {
            val flood = j in J_FLOODS
            if (n < lastN) return this
            if (n == lastN) {
                // the tag's t0 is its first flood copy: an A already on n keeps its time (copy 10, copy 1)
                if (flood) return copy(a = if (a?.n == n) a else Grid(n, t), cands = emptyList(), known = true)
                if (liveAck && cands.none { it.n == n } && a?.n != n) return addCand(Grid(n, seen[n] ?: t))
                return this
            }
            var s = this
            val m = lastN
            if (sync) {
                val g = grids().firstOrNull { it.n == n - 1 } ?: seen[n - 1]?.let { Grid(n - 1, it) }
                s = s.copy(a = g, cands = emptyList(), known = g != null)
            } else if (m >= 0) {
                if (n == m + 1) s = s.copy(cands = s.cands.filter { it.n != m })
                else if (n > m + 2 && !(still && !dueInGap)) s = s.copy(known = false)
            }
            s = s.copy(seen = (s.seen + (n to t)).filterKeys { it >= n - 8 }, lastN = n)
            return when {
                flood -> s.copy(a = Grid(n, t), cands = emptyList(), known = true)
                liveAck -> s.addCand(Grid(n, t))
                else -> s
            }
        }

        private fun addCand(g: Grid): AppAnchor =
            if (cands.size >= MAX_CANDIDATES) copy(known = false) else copy(cands = cands + g)

        /** A confirmed command settles the grids: the send it answered was on grid [nGrid]. */
        fun onCommandConfirmed(nGrid: Long): AppAnchor {
            val g = grids().firstOrNull { it.n == nGrid } ?: return this
            return copy(a = g, cands = emptyList(), known = true)
        }
    }
}

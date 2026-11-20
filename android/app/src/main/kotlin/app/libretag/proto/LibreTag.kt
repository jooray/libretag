package app.libretag.proto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LibreTag wire protocol v2 (PROTOCOL.md in the LibreTag repo). Mirrors host/src/libretag/lt2.py;
 * tests check it against host/tests/vectors/lt2.json.
 */
object LibreTag {
    const val KEY_SIZE = 32
    const val EPOCH_REPORTS = 64
    const val TAG_LEN = 4
    const val MAC_LEN = 8
    /** report sealing v2 (j = 0, 1 on air): 6 B mac, 3 B commitment c to the mailbox key at P[4..7) */
    const val RMAC_LEN = 6
    const val COMMIT_LEN = 3
    const val COMMIT_OFF = 4
    const val REPORT_PT_LEN = 33
    const val REPORT_PT_LEN_STEALTH = 39
    const val REPORT_P_LEN = 45
    const val REPORT_P_LEN_STEALTH = 51
    const val CMD_PT_LEN = 17
    const val CMD_P_LEN = 29
    const val MAX_NB = 5
    const val TYPE_REPORT = 0x21
    const val TYPE_COMMAND = 0x22
    const val TYPE_BLE = 0x23
    const val TYPE_WIFI = 0x24
    const val BLE_PT_LEN = 129
    const val BLE_PT_LEN_STEALTH = 135
    const val BLE_P_LEN = 141
    const val BLE_P_LEN_STEALTH = 147
    const val BLE_MAX_PACKETS = 4

    /** copies j of one report counter n (PROTOCOL.md 2) */
    const val J_HOME = 0
    const val J_BORDER = 1
    const val J_HIST = 2
    const val J_HIST_SILENT = 3
    const val J_WIFI = 4
    /** BLE packet k (0..3) after report n is copy j = J_BLE + k */
    const val J_BLE = 5

    const val F_HAS_FIX = 0x01
    const val F_CMD_ACK = 0x02
    const val F_LOW_BATT = 0x04
    const val F_NB_DISCOVER = 0x08
    /** the last report before a quiet stretch (zone or owner present) */
    const val F_QUIET = 0x10
    /** with F_QUIET: the reason is the owner's device nearby (else a home zone) */
    const val F_QUIET_OWNER = 0x20
    /** bytes 28..30 carry a BLE summary instead of the 5th repeater */
    const val F_BLE_SUM = 0x40
    /** byte 15 bit 7: Wi-Fi layout of bytes 16..30 */
    const val NB_WIFI = 0x80
    /** 0.7: while quiet the tag listens at checks; its IDLE interval is the configured one */
    const val F_CHECKS = 0x80
    /** 0.7, byte 2 bit 3: with F_QUIET and without F_QUIET_OWNER, the reason is "parked" (STILL) */
    const val M_PARKED = 0x08
    /** mode values in byte 2 (bits 0..1; 0.9.4, bit 2 was always 0 before) */
    const val M_MODE = 0x03
    /** 0.9.4, byte 2 bit 2: the first report since a boot */
    const val M_BOOT = 0x04
    /** 0.9.4: byte 14 of a boot report without a fix, bits 0..2 (the words `status` prints as last_reset=) */
    val BOOT_CAUSES = listOf("none", "pin", "power", "soft", "debug", "brownout", "watchdog", "crash")

    const val NOSTR_REPORT_KIND = 4243
    const val NOSTR_COMMAND_KIND = 4244

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun u32(n: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n.toInt()).array()

    fun u16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    fun epoch(n: Long): Long = n / EPOCH_REPORTS

    internal fun ctr(key: ByteArray, iv8: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv8 + ByteArray(16 - iv8.size)))
        return c.doFinal(data)
    }

    // ---------------------------------------------------------------- SIV

    private fun seal(kd: ByteArray, tag: ByteArray, pt: ByteArray): ByteArray {
        val mac = hmac(kd.copyOfRange(16, 32), tag, pt).copyOf(MAC_LEN)
        return tag + ctr(kd.copyOf(16), mac, pt) + mac
    }

    private fun open(kd: ByteArray, p: ByteArray): ByteArray? {
        val tag = p.copyOf(TAG_LEN)
        val mac = p.copyOfRange(p.size - MAC_LEN, p.size)
        val pt = ctr(kd.copyOf(16), mac, p.copyOfRange(TAG_LEN, p.size - MAC_LEN))
        return if (MessageDigest.isEqual(hmac(kd.copyOfRange(16, 32), tag, pt).copyOf(MAC_LEN), mac)) pt else null
    }

    fun reportTag(viewE: ByteArray, n: Long, j: Int): ByteArray =
        hmac(viewE, "LT2-x".toByteArray(), u32(n), byteArrayOf(j.toByte())).copyOf(TAG_LEN)

    private fun reportKd(viewE: ByteArray, n: Long, j: Int) =
        hmac(viewE, "LT2-r".toByteArray(), u32(n), byteArrayOf(j.toByte()))

    private fun reportKd2(viewE: ByteArray, n: Long, j: Int) =
        hmac(viewE, "LT2-r2".toByteArray(), u32(n), byteArrayOf(j.toByte()))

    private val SECP_N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    /** Mailbox key of report n (PROTOCOL.md 4): every kind 4244 event for report n is signed with it.
     *  Needs the command chain, so a view share cannot derive it. */
    fun mailboxSk(cmdE: ByteArray, n: Long): ByteArray {
        var v = BigInteger(1, hmac(cmdE, "LT2-mb".toByteArray(), u32(n))).mod(SECP_N)
        if (v.signum() == 0) v = BigInteger.ONE
        val b = v.toByteArray()
        return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
    }

    /** pk_n = sk_n * G, x-only (BIP-340). */
    fun mailboxPk(cmdE: ByteArray, n: Long): ByteArray =
        fr.acinq.secp256k1.Secp256k1.pubkeyCreate(mailboxSk(cmdE, n)).copyOfRange(1, 33)

    /** A bridge's check: does the event key pk fit the commitment of report P? */
    fun commitMatches(pk: ByteArray, p: ByteArray): Boolean =
        (p.size == REPORT_P_LEN || p.size == REPORT_P_LEN_STEALTH) &&
            MessageDigest.isEqual(reportCommit(pk, p), p.copyOfRange(COMMIT_OFF, COMMIT_OFF + COMMIT_LEN))

    /** c = SHA-256("LT2-mbc" || pk_n || x || ct || mac)[0:3]: P without bytes 4..6. */
    fun reportCommit(pk: ByteArray, p: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update("LT2-mbc".toByteArray()); update(pk); update(p, 0, TAG_LEN)
        update(p, COMMIT_OFF + COMMIT_LEN, p.size - COMMIT_OFF - COMMIT_LEN)
        digest().copyOf(COMMIT_LEN)
    }

    /** Copies 0 and 1 (on air): v2 sealing, pt is the 33 B plaintext (its type byte is not sent), pk = pk_n.
     *  History records (j = 2, 3): the plain seal, pk unused. */
    fun sealReport(viewE: ByteArray, n: Long, j: Int, pt: ByteArray, pk: ByteArray? = null): ByteArray {
        if (j != J_HOME && j != J_BORDER) return seal(reportKd(viewE, n, j), reportTag(viewE, n, j), pt)
        requireNotNull(pk) { "a report on air needs pk_n" }
        val kd = reportKd2(viewE, n, j)
        val x = reportTag(viewE, n, j)
        val pt1 = pt.copyOfRange(1, pt.size)
        val mac = hmac(kd.copyOfRange(16, 32), x, pt1).copyOf(RMAC_LEN)
        val body = ctr(kd.copyOf(16), mac, pt1) + mac
        return x + reportCommit(pk, x + ByteArray(COMMIT_LEN) + body) + body
    }

    fun openReport(viewE: ByteArray, n: Long, j: Int, p: ByteArray): Report? {
        if (p.size != REPORT_P_LEN && p.size != REPORT_P_LEN_STEALTH) return null
        if (!MessageDigest.isEqual(p.copyOf(TAG_LEN), reportTag(viewE, n, j))) return null
        if (j != J_HOME && j != J_BORDER) return open(reportKd(viewE, n, j), p)?.let { Report.decode(it) }
        val kd = reportKd2(viewE, n, j)
        val mac = p.copyOfRange(p.size - RMAC_LEN, p.size)
        val pt1 = ctr(kd.copyOf(16), mac, p.copyOfRange(COMMIT_OFF + COMMIT_LEN, p.size - RMAC_LEN))
        if (!MessageDigest.isEqual(hmac(kd.copyOfRange(16, 32), p.copyOf(TAG_LEN), pt1).copyOf(RMAC_LEN), mac)) return null
        return Report.decode(byteArrayOf(TYPE_REPORT.toByte()) + pt1.copyOf(REPORT_PT_LEN - 1))
    }

    /** A BLE or Wi-Fi packet: sealed like report n, copy j (J_WIFI, J_BLE + k). */
    fun sealExtra(viewE: ByteArray, n: Long, j: Int, pt: ByteArray, stealth: Boolean = false): ByteArray =
        seal(reportKd(viewE, n, j), reportTag(viewE, n, j), if (stealth) pt + ByteArray(6) else pt)

    /** A [WifiPacket] (j = 4) or [BlePacket] (j = 5..8) that belongs to report n, else null. */
    fun openExtra(viewE: ByteArray, n: Long, j: Int, p: ByteArray): Any? {
        if (p.size < TAG_LEN || !MessageDigest.isEqual(p.copyOf(TAG_LEN), reportTag(viewE, n, j))) return null
        if (j == J_WIFI && (p.size == REPORT_P_LEN || p.size == REPORT_P_LEN_STEALTH)) {
            val pt = open(reportKd(viewE, n, j), p) ?: return null
            return if ((pt[0].toInt() and 0xFF) == TYPE_WIFI) WifiPacket.decode(pt) else null
        }
        if (j >= J_BLE && j < J_BLE + BLE_MAX_PACKETS && (p.size == BLE_P_LEN || p.size == BLE_P_LEN_STEALTH)) {
            val pt = open(reportKd(viewE, n, j), p) ?: return null
            return if ((pt[0].toInt() and 0xFF) == TYPE_BLE) BlePacket.decode(pt) else null
        }
        return null
    }

    /** Byte 31: 0 = not stated; 1..120 = v x 30 s; 121..255 = 1 h + (v - 120) x 15 min. */
    fun nextInEncode(seconds: Long?): Int {
        if (seconds == null || seconds <= 0) return 0
        if (seconds <= 3600 + 450) return ((seconds + 15) / 30).coerceIn(1, 120).toInt()
        return minOf(255L, 120 + (seconds - 3600 + 450) / 900).toInt()
    }

    fun nextInDecode(v: Int): Long? = when {
        v == 0 -> null
        v <= 120 -> v * 30L
        else -> 3600L + (v - 120) * 900L
    }

    /** Byte 32: 1 + floor(mAh / 10), 255 = 2540 mAh or more; 0 = not counted. */
    fun energyEncode(mah: Double?): Int {
        if (mah == null || mah < 0) return 0
        return minOf(255, 1 + Math.floor(mah / 10).toInt())
    }

    /** Lower bound of the used charge in mAh (true value within +10 mAh), null = not counted. */
    fun energyDecode(v: Int): Int? = if (v == 0) null else (v - 1) * 10

    /** 5-bit RSSI of a Wi-Fi AP: dBm = -95 + 2 q. */
    fun qRssi(q: Int): Int = -95 + 2 * (q and 0x1F)

    /**
     * BLE resolvable private address check (Core spec Vol 3 Part H 2.2.2). [irk] and [addr] as
     * written, most significant byte first.
     */
    fun rpaMatches(irk: ByteArray, addr: ByteArray): Boolean {
        if (irk.size != 16 || addr.size != 6 || (addr[0].toInt() and 0xC0) != 0x40) return false
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk, "AES"))
        val r = c.doFinal(ByteArray(13) + addr.copyOf(3))
        return MessageDigest.isEqual(r.copyOfRange(13, 16), addr.copyOfRange(3, 6))
    }

    /** k = 0: the window right after report n (0.6); k >= 1: check k after it (0.7, u16(k) appended). */
    private fun cmdSuffix(n: Long, k: Int): ByteArray = if (k == 0) u32(n) else u32(n) + u16(k)

    fun commandTag(cmdE: ByteArray, n: Long, k: Int = 0): ByteArray =
        hmac(cmdE, "LT2-cx".toByteArray(), cmdSuffix(n, k)).copyOf(TAG_LEN)

    private fun commandKd(cmdE: ByteArray, n: Long, k: Int) = hmac(cmdE, "LT2-c".toByteArray(), cmdSuffix(n, k))

    fun sealCommand(cmdE: ByteArray, n: Long, cmd: Command, k: Int = 0): ByteArray =
        seal(commandKd(cmdE, n, k), commandTag(cmdE, n, k), cmd.encode())

    fun openCommand(cmdE: ByteArray, n: Long, p: ByteArray, k: Int = 0): Command? {
        if (p.size != CMD_P_LEN || !MessageDigest.isEqual(p.copyOf(TAG_LEN), commandTag(cmdE, n, k))) return null
        return open(commandKd(cmdE, n, k), p)?.let { Command.decode(it) }
    }

    /**
     * Jitter of check i after report n, in seconds (0.7 "Schedule"): the low two bytes of
     * HMAC(C_e(n), "LT2-w" || u32(n) || u16(i)) mod span, minus span / 2; span = base / 5.
     */
    fun checkJitter(cmdE: ByteArray, n: Long, i: Int, baseS: Long): Long {
        val span = baseS / 5
        if (span <= 0) return 0
        val h = hmac(cmdE, "LT2-w".toByteArray(), u32(n), u16(i))
        val v = (h[0].toInt() and 0xFF) or ((h[1].toInt() and 0xFF) shl 8)
        return (v % span) - span / 2
    }

    /**
     * Offsets of checks k = 1, 2, … after report n (seconds from its transmission), at most [max]
     * and only before the heartbeat [untilS] (a check at or after it is not made).
     */
    fun checkOffsets(cmdE: ByteArray, n: Long, intervalMin: Int, untilS: Long, max: Int = 200): List<Long> {
        val base = intervalMin * 60L
        if (base <= 0) return emptyList()
        val out = ArrayList<Long>()
        var t = 0L
        var i = 1
        while (out.size < max) {
            t += base + checkJitter(cmdE, n, i, base)
            if (t >= untilS) break
            out += t
            i++
        }
        return out
    }

    fun usbAuth(usbKey: ByteArray, challenge: ByteArray): ByteArray =
        hmac(usbKey, "LT2-auth".toByteArray(), challenge).copyOf(16)

    fun hexToBytes(hex: String): ByteArray? {
        val s = hex.trim().lowercase().filter { !it.isWhitespace() }
        if (s.length % 2 != 0 || s.any { it !in "0123456789abcdef" }) return null
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

/** Everything the owner's app derives from the pairing secret K0 (PROTOCOL.md 1). */
class Keys(val k0: ByteArray) {
    val id: ByteArray by lazy { LibreTag.hmac(k0, "LT2-id".toByteArray()).copyOf(8) }
    val usbKey: ByteArray by lazy { LibreTag.hmac(k0, "LT2-usb".toByteArray()) }
    val dmSk: ByteArray by lazy {
        val v = BigInteger(1, LibreTag.hmac(k0, "LT2-dm".toByteArray())).mod(SECP256K1_N)
        val b = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        ByteArray(32 - b.size) + b
    }
    /** What the tag does: seal report n, copy j, with the view key and (on air) pk_n. */
    fun sealReport(n: Long, j: Int, pt: ByteArray): ByteArray {
        val e = LibreTag.epoch(n)
        val pk = if (j == LibreTag.J_HOME || j == LibreTag.J_BORDER) LibreTag.mailboxPk(cmd(e), n) else null
        return LibreTag.sealReport(view(e), n, j, pt, pk)
    }
    private val view0 = LibreTag.hmac(k0, "LT2-view".toByteArray())
    private val cmd0 = LibreTag.hmac(k0, "LT2-cmd".toByteArray())
    private val viewCache = mutableMapOf<Long, ByteArray>()
    private val cmdCache = mutableMapOf<Long, ByteArray>()

    @Synchronized fun view(epoch: Long): ByteArray = chain(view0, viewCache, epoch)
    @Synchronized fun cmd(epoch: Long): ByteArray = chain(cmd0, cmdCache, epoch)

    private fun chain(start: ByteArray, cache: MutableMap<Long, ByteArray>, epoch: Long): ByteArray {
        cache[epoch]?.let { return it }
        val from = cache.keys.filter { it < epoch }.maxOrNull()
        var e = from ?: 0L
        var k = from?.let { cache[it]!! } ?: start
        while (e < epoch) {
            k = LibreTag.hmac(k, "LT2-next".toByteArray())
            e++
        }
        cache[epoch] = k
        return k
    }

    companion object {
        val SECP256K1_N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    }
}

enum class Mode { IDLE, TRACK, LISTEN, LOST }

data class LastFix(val time: Long, val lat: Double, val lon: Double)

/** A repeater near the tracker: first two bytes of its public key (hex) and an SNR in dB. */
data class Neighbour(val hash: String, val snrDb: Double)

/** LT2 report plaintext (PROTOCOL.md 2). Without [hasFix] the position is the last fix (if [fixTime] > 0). */
data class Report(
    val hasFix: Boolean,
    val fixTime: Long,
    val lat: Double,
    val lon: Double,
    val haccM: Int?,
    val batteryPct: Int?,
    val mode: Mode,
    val cmdAck: Boolean,
    val cmdSeq: Int,
    val lowBatt: Boolean,
    val nbTotal: Int,
    val nbDiscover: Boolean,
    val neighbours: List<Neighbour>,
    /** the last report before a quiet stretch */
    val quiet: Boolean = false,
    /** with [quiet]: the owner's device is nearby (else a home zone) */
    val quietOwner: Boolean = false,
    /** 0.7: with [quiet] and not [quietOwner], the tag is parked (has not moved for a few reports) */
    val parked: Boolean = false,
    /** 0.7 (CHECKS): while quiet the tag listens at checks on its configured IDLE interval */
    val checks: Boolean = false,
    /** seconds to the next wake-up that transmits (30 s / 15 min steps), null = not stated */
    val nextInS: Long? = null,
    /** charge used since the last full charge, lower bound in mAh (10 mAh steps), null = not counted */
    val energyMah: Int? = null,
    /** BLE summary (bytes 28..30), at most 4 neighbours then */
    val ble: BleSummary? = null,
    /** Wi-Fi layout (bytes 16..30), at most 1 neighbour then */
    val wifi: WifiFields? = null,
    /** 0.9.4: the first report since a boot (byte 2 bit 2) */
    val boot: Boolean = false,
    /** 0.9.4, with [boot] and without a fix: the reset cause word (none, pin, power, soft, debug, brownout,
     *  watchdog, crash), from byte 14; null with a fix or in the Wi-Fi seed2 layout */
    val bootCause: String? = null,
    /** 0.9.4, as [bootCause]: resets in a row, capped at 31 */
    val bootResets: Int? = null,
) {
    val lastFix: LastFix? get() = if (!hasFix && fixTime > 0) LastFix(fixTime, lat, lon) else null

    /** "owner", "parked", "zone" or null when not [quiet] */
    val quietReason: String? get() = when {
        !quiet -> null
        quietOwner -> "owner"
        parked -> "parked"
        else -> "zone"
    }

    companion object {
        fun decode(pt: ByteArray): Report? {
            if ((pt.size != LibreTag.REPORT_PT_LEN && pt.size != LibreTag.REPORT_PT_LEN_STEALTH) ||
                (pt[0].toInt() and 0xFF) != LibreTag.TYPE_REPORT) return null
            fun s24(o: Int): Int {
                val v = (pt[o].toInt() and 0xFF) or ((pt[o + 1].toInt() and 0xFF) shl 8) or ((pt[o + 2].toInt() and 0xFF) shl 16)
                return if (v and 0x800000 != 0) v - (1 shl 24) else v
            }
            val flags = pt[1].toInt() and 0xFF
            val modeB = pt[2].toInt() and 0xFF
            val batt = pt[3].toInt() and 0xFF
            val t = ByteBuffer.wrap(pt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val hacc = pt[14].toInt() and 0xFF
            val counts = pt[15].toInt() and 0xFF
            val wifiL = counts and LibreTag.NB_WIFI != 0
            val bleL = flags and LibreTag.F_BLE_SUM != 0 && !wifiL
            // a second tail in the repeater's slot: byte 30 bit 7 (PROTOCOL.md 2.2)
            val tail2L = wifiL && (pt[30].toInt() and 0x80) != 0
            val maxNb = if (wifiL) (if (tail2L) 0 else 1) else if (bleL) 4 else LibreTag.MAX_NB
            fun u(o: Int) = pt[o].toInt() and 0xFF
            // Wi-Fi layout without any fix time: bytes 8..14 carry a second full BSSID (PROTOCOL.md 2.2)
            val seed2 = wifiL && t == 0L && (8 until 14).any { pt[it].toInt() != 0 }
            val wifi = if (wifiL) WifiFields(
                seed = pt.copyOfRange(19, 25), seedRssi = LibreTag.qRssi(u(25)), apCount = u(25) shr 5,
                tail = if (u(29) and 0x80 != 0) pt.copyOfRange(26, 29) else null,
                tailRssi = if (u(29) and 0x80 != 0) LibreTag.qRssi(u(29)) else -95,
                seed2 = if (seed2) pt.copyOfRange(8, 14) else null,
                seed2Rssi = if (seed2) LibreTag.qRssi(u(14)) else -95,
                tail2 = if (tail2L) pt.copyOfRange(16, 19) else null,
                tail2Rssi = if (tail2L) LibreTag.qRssi(u(30)) else -95,
            ) else null
            val ble = if (bleL) BleSummary(u(28), u(29), if (u(30) == 0) null else pt[30].toInt()) else null
            // 0.9.4: a boot report without a fix carries the reset information in byte 14
            val boot = modeB and LibreTag.M_BOOT != 0
            val bootInfo = boot && flags and LibreTag.F_HAS_FIX == 0 && !seed2
            val nbs = (0 until minOf((counts shr 4) and 0x07, maxNb)).map { i ->
                val o = 16 + 3 * i
                Neighbour(LibreTag.bytesToHex(pt.copyOfRange(o, o + 2)), pt[o + 2] / 4.0)
            }
            return Report(
                hasFix = flags and LibreTag.F_HAS_FIX != 0,
                fixTime = t,
                lat = if (t != 0L) s24(8) * 90.0 / (1 shl 23) else 0.0,
                lon = if (t != 0L) s24(11) * 180.0 / (1 shl 23) else 0.0,
                haccM = if (hacc == 255 || seed2 || bootInfo) null else hacc,
                batteryPct = if (batt == 255) null else batt,
                mode = Mode.entries.getOrElse(modeB and LibreTag.M_MODE) { Mode.IDLE },
                cmdAck = flags and LibreTag.F_CMD_ACK != 0,
                cmdSeq = modeB shr 4,
                lowBatt = flags and LibreTag.F_LOW_BATT != 0,
                nbTotal = counts and 0x0F,
                nbDiscover = flags and LibreTag.F_NB_DISCOVER != 0,
                neighbours = nbs,
                quiet = flags and LibreTag.F_QUIET != 0,
                quietOwner = flags and LibreTag.F_QUIET_OWNER != 0,
                parked = modeB and LibreTag.M_PARKED != 0,
                checks = flags and LibreTag.F_CHECKS != 0,
                nextInS = LibreTag.nextInDecode(u(31)),
                energyMah = LibreTag.energyDecode(u(32)),
                ble = ble,
                wifi = wifi,
                boot = boot,
                bootCause = if (bootInfo) LibreTag.BOOT_CAUSES[hacc and 0x07] else null,
                bootResets = if (bootInfo) hacc shr 3 else null,
            )
        }
    }
}

/** LT2 command plaintext (PROTOCOL.md 3). */
data class Command(val seq: Int, val op: Int, val args: ByteArray = ByteArray(0)) {
    fun encode(): ByteArray =
        byteArrayOf(LibreTag.TYPE_COMMAND.toByte(), seq.toByte(), op.toByte()) + args.copyOf(14)

    companion object {
        const val OP_SET_MODE = 1
        const val OP_SET_IDLE = 3
        const val OP_BUZZ = 4
        const val OP_BLE_SCAN = 5
        /** 0.9.2: open the Bluetooth console window (PROTOCOL.md 3, 6.2) */
        const val OP_BLE_CONSOLE = 6

        private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

        fun decode(pt: ByteArray): Command? {
            if (pt.size != LibreTag.CMD_PT_LEN || (pt[0].toInt() and 0xFF) != LibreTag.TYPE_COMMAND) return null
            return Command(pt[1].toInt() and 0xFF, pt[2].toInt() and 0xFF, pt.copyOfRange(3, 17))
        }

        fun setMode(seq: Int, mode: Mode, intervalS: Int = 0, durationMin: Int = 0) =
            Command(seq, OP_SET_MODE, byteArrayOf(mode.ordinal.toByte()) + le16(intervalS) + le16(durationMin))

        /** SET_IDLE check_s: no listening at checks (0 keeps the value) */
        const val CHECK_OFF = 0xFF

        /** 0 keeps the current value; [checkS] (0.7, offset 7): listening at checks 1..180 s (0.7.2; older firmware clamps to 60), [CHECK_OFF] turns it off */
        fun setIdle(seq: Int, intervalMin: Int, listenS: Int = 0, checkS: Int = 0, reportEvery: Int = 0) =
            Command(seq, OP_SET_IDLE, le16(intervalMin) + le16(listenS) +
                byteArrayOf((if (checkS == CHECK_OFF) CHECK_OFF else checkS.coerceIn(0, 180)).toByte(),
                    reportEvery.coerceIn(0, 24).toByte()))

        /** report_every of a SET_IDLE command (0.7.3, offset 8), null when it keeps the value */
        fun idleReportEvery(c: Command): Int? = if (c.op != OP_SET_IDLE || c.args.size < 6) null
            else (c.args[5].toInt() and 0xFF).takeIf { it > 0 }

        /** interval_s of a SET_MODE command (null for other commands) */
        fun modeIntervalS(c: Command): Int? = if (c.op != OP_SET_MODE || c.args.size < 3) null
            else ((c.args[1].toInt() and 0xFF) or ((c.args[2].toInt() and 0xFF) shl 8)).takeIf { it > 0 }

        /** duration_min of a SET_MODE command (null for other commands or 0 = the tag's default) */
        fun modeDurationMin(c: Command): Int? = if (c.op != OP_SET_MODE || c.args.size < 5) null
            else ((c.args[3].toInt() and 0xFF) or ((c.args[4].toInt() and 0xFF) shl 8)).takeIf { it > 0 }

        /** interval_min of a SET_IDLE command (null for other commands or 0 = keep) */
        fun idleIntervalMin(c: Command): Int? = if (c.op != OP_SET_IDLE || c.args.size < 2) null
            else ((c.args[0].toInt() and 0xFF) or ((c.args[1].toInt() and 0xFF) shl 8)).takeIf { it > 0 }

        /** beep for [seconds] (trackers with a buzzer) */
        fun buzz(seq: Int, seconds: Int) = Command(seq, OP_BUZZ, byteArrayOf(seconds.coerceIn(1, 60).toByte()))

        /** scan Bluetooth now (0 = 8 s, at most 20) and send up to [packets] BLE packets (0 = 2, at most 4) */
        fun bleScan(seq: Int, seconds: Int = 0, active: Boolean = false, packets: Int = 0) = Command(
            seq, OP_BLE_SCAN,
            byteArrayOf(seconds.coerceIn(0, 20).toByte(), (if (active) 1 else 0).toByte(),
                packets.coerceIn(0, LibreTag.BLE_MAX_PACKETS).toByte()),
        )

        /**
         * 0.9.2: the tag opens its Bluetooth console at the wake-up that takes this, as after a boot.
         * [windowS] in units of 10 s (0 = 180 s; the tag clamps to 30..600 s). It ends before offset
         * 8, so it also goes as a Bluetooth command advert (3.1).
         */
        fun bleConsole(seq: Int, windowS: Int = 0) =
            Command(seq, OP_BLE_CONSOLE, byteArrayOf(((windowS.coerceIn(0, 2550) + 9) / 10).toByte()))
    }
}

/**
 * Which report counters to try for a tracker, as a table of 4-byte tags (PROTOCOL.md 2): a wide
 * window ahead (reboots skip 16, TRACK burns 30 an hour, the phone may be away for days) and a
 * short one behind, so a report that arrives late (relay copy, a bridge's backlog) still lands in
 * the history. Both copies j = 0, 1 (home and border network). [seen] counters are never reopened.
 */
class ReportWindow(private val keys: Keys, var lastN: Long, seen: Collection<Long> = emptyList()) {
    private val seen = java.util.TreeSet(seen)
    private var table: Map<String, Pair<Long, Int>> = build()
    private var extra: Map<String, Pair<Long, Int>> = buildExtra()

    /** BLE and Wi-Fi packets follow a report within seconds: only near the newest report. */
    private fun buildExtra(): Map<String, Pair<Long, Int>> {
        val base = maxOf(lastN, 0L)
        val m = HashMap<String, Pair<Long, Int>>()
        for (n in maxOf(0L, base - EXTRA_BACK)..base + EXTRA_AHEAD) {
            val v = keys.view(LibreTag.epoch(n))
            for (j in LibreTag.J_WIFI until LibreTag.J_BLE + LibreTag.BLE_MAX_PACKETS)
                m[LibreTag.bytesToHex(LibreTag.reportTag(v, n, j))] = n to j
        }
        return m
    }

    private fun build(): Map<String, Pair<Long, Int>> {
        val from = if (lastN < 0) 0L else maxOf(0L, lastN - BACK)
        val to = if (lastN < 0) FIRST_SYNC else lastN + 1 + AHEAD
        val m = HashMap<String, Pair<Long, Int>>()
        for (n in from until to) {
            if (n in seen) continue
            val v = keys.view(LibreTag.epoch(n))
            for (j in 0..1) m[LibreTag.bytesToHex(LibreTag.reportTag(v, n, j))] = n to j
        }
        return m
    }

    /** Tags of the next [count] counters (both copies), for a relay filter. */
    fun tagsAhead(count: Int): List<String> = table.entries.filter { it.value.first > lastN }
        .sortedBy { it.value.first }.take(2 * count).map { it.key }

    fun tryOpen(p: ByteArray): Triple<Long, Int, Report>? {
        if (p.size < LibreTag.TAG_LEN) return null
        val (n, j) = table[LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN))] ?: return null
        val r = LibreTag.openReport(keys.view(LibreTag.epoch(n)), n, j, p) ?: return null
        seen.add(n)
        if (n > lastN) {
            lastN = n
            while (seen.isNotEmpty() && seen.first() < lastN - BACK) seen.pollFirst()
            table = build()
            extra = buildExtra()
        } else {
            table = table.filterValues { it.first != n }
        }
        return Triple(n, j, r)
    }

    /** A BLE (j = 5..8, [BlePacket]) or Wi-Fi (j = 4, [WifiPacket]) packet that belongs to report n. */
    fun tryOpenExtra(p: ByteArray): Triple<Long, Int, Any>? {
        if (p.size < LibreTag.TAG_LEN) return null
        val (n, j) = extra[LibreTag.bytesToHex(p.copyOf(LibreTag.TAG_LEN))] ?: return null
        val x = LibreTag.openExtra(keys.view(LibreTag.epoch(n)), n, j, p) ?: return null
        return Triple(n, j, x)
    }

    companion object {
        const val AHEAD = 2048L
        const val BACK = 256L
        const val FIRST_SYNC = 4096L
        const val EXTRA_BACK = 8L
        const val EXTRA_AHEAD = 64L
    }
}

/** Bytes 28..30 with F_BLE_SUM: what the last BLE scan saw (PROTOCOL.md 2.3). */
data class BleSummary(val devices: Int, val stable: Int, val stableRssi: Int?)

/** Bytes 16..30 in the Wi-Fi layout: one full BSSID (as written, first byte first) and one tail; without a fix time a second full BSSID in bytes 8..14. */
class WifiFields(
    val seed: ByteArray,
    val seedRssi: Int,
    /** APs in the scan, 7 = 7 or more */
    val apCount: Int,
    /** last 3 bytes of a second BSSID */
    val tail: ByteArray?,
    val tailRssi: Int,
    /** a second full BSSID in bytes 8..13, only without any fix time (fix time 0, PROTOCOL.md 2.2) */
    val seed2: ByteArray? = null,
    val seed2Rssi: Int = -95,
    /** a second tail in the repeater's slot (bytes 16..18, RSSI in byte 30 with bit 7 set) */
    val tail2: ByteArray? = null,
    val tail2Rssi: Int = -95,
) {
    val seedHex: String get() = LibreTag.bytesToHex(seed)
    val tailHex: String? get() = tail?.let { LibreTag.bytesToHex(it) }
    val seed2Hex: String? get() = seed2?.let { LibreTag.bytesToHex(it) }
    val tail2Hex: String? get() = tail2?.let { LibreTag.bytesToHex(it) }
    override fun equals(other: Any?) = other is WifiFields && seed.contentEquals(other.seed) &&
        seedRssi == other.seedRssi && apCount == other.apCount && tailRssi == other.tailRssi &&
        tail.contentEquals(other.tail) && seed2.contentEquals(other.seed2) && seed2Rssi == other.seed2Rssi &&
        tail2.contentEquals(other.tail2) && tail2Rssi == other.tail2Rssi
    override fun hashCode() = seed.contentHashCode() * 31 + apCount
    override fun toString() = "WifiFields(seed=$seedHex, seedRssi=$seedRssi, apCount=$apCount, tail=$tailHex, tailRssi=$tailRssi, seed2=$seed2Hex, tail2=$tail2Hex)"
}

/** Wi-Fi packet (type 0x24, copy j = 4): one more full BSSID and up to six tails (3 bytes, dBm). */
class WifiPacket(val apCount: Int, val seed: ByteArray, val seedRssi: Int, val tails: List<Pair<ByteArray, Int>>) {
    val seedHex: String get() = LibreTag.bytesToHex(seed)

    companion object {
        fun decode(pt: ByteArray): WifiPacket? {
            if ((pt.size != LibreTag.REPORT_PT_LEN && pt.size != LibreTag.REPORT_PT_LEN_STEALTH) ||
                (pt[0].toInt() and 0xFF) != LibreTag.TYPE_WIFI) return null
            val tails = (0 until 6).mapNotNull { i ->
                val o = 9 + 4 * i
                val m = pt[o + 3].toInt() and 0xFF
                if (m and 0x80 != 0) pt.copyOfRange(o, o + 3) to LibreTag.qRssi(m) else null
            }
            return WifiPacket(pt[1].toInt() and 0xFF, pt.copyOfRange(2, 8), LibreTag.qRssi(pt[8].toInt() and 0xFF), tails)
        }
    }
}

/** Header of every BLE packet: the whole scan in numbers. */
data class BleSurvey(
    val seconds: Int,
    val active: Boolean,
    val devices: Int,
    val stable: Int,
    val rotating: Int,
    val rotatingApple: Int,
    val rotatingMicrosoft: Int,
)

/** One record of a BLE packet; [ad] holds AD structures as on air (len, type, data). */
class BleDevice(
    /** 6 bytes as written (first byte = most significant) */
    val addr: ByteArray,
    /** ADDR_PUBLIC, ADDR_STATIC, ADDR_RPA or ADDR_NRPA */
    val kind: Int,
    val rssi: Int,
    val heard: Int,
    val connectable: Boolean,
    val scanRsp: Boolean,
    /** the owner's anchor device */
    val anchor: Boolean,
    val ad: ByteArray,
) {
    val addrHex: String get() = LibreTag.bytesToHex(addr)

    /** AD structures as (type, data) */
    fun ads(): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i < ad.size && ad[i].toInt() != 0) {
            val ln = ad[i].toInt() and 0xFF
            if (i + 1 + ln > ad.size) break
            out.add((ad[i + 1].toInt() and 0xFF) to ad.copyOfRange(i + 2, i + 1 + ln))
            i += 1 + ln
        }
        return out
    }

    /** complete or shortened local name (AD 0x09 / 0x08) */
    val name: String? get() = ads().firstOrNull { it.first == 0x08 || it.first == 0x09 }?.second?.toString(Charsets.UTF_8)

    /** Bluetooth SIG company id of the first manufacturer data (AD 0xFF) */
    val company: Int? get() = ads().firstOrNull { it.first == 0xFF && it.second.size >= 2 }?.second
        ?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }

    companion object {
        const val ADDR_PUBLIC = 0
        const val ADDR_STATIC = 1
        const val ADDR_RPA = 2
        const val ADDR_NRPA = 3
        const val BI_CONNECTABLE = 0x04
        const val BI_SCAN_RSP = 0x08
        const val BI_ANCHOR = 0x10
    }
}

/** BLE surroundings packet (type 0x23, copy j = 5 + k), PROTOCOL.md 2.3. */
class BlePacket(val k: Int, val total: Int, val truncated: Boolean, val survey: BleSurvey, val devices: List<BleDevice>) {
    companion object {
        private const val HDR_LEN = 8
        private const val REC_MIN = 10

        fun decode(pt: ByteArray): BlePacket? {
            if ((pt.size != LibreTag.BLE_PT_LEN && pt.size != LibreTag.BLE_PT_LEN_STEALTH) ||
                (pt[0].toInt() and 0xFF) != LibreTag.TYPE_BLE) return null
            fun u(o: Int) = pt[o].toInt() and 0xFF
            val b1 = u(1)
            val sv = BleSurvey(u(2), b1 and 0x10 != 0, u(3), u(4), u(5), u(6), u(7))
            val devs = ArrayList<BleDevice>()
            var i = HDR_LEN
            while (i < LibreTag.BLE_PT_LEN && u(i) != 0) {
                val ln = u(i)
                if (ln < REC_MIN - 1 || i + 1 + ln > LibreTag.BLE_PT_LEN) break
                val r = pt.copyOfRange(i + 1, i + 1 + ln)
                val info = r[6].toInt() and 0xFF
                devs.add(BleDevice(
                    addr = r.copyOfRange(0, 6), kind = info and 3, rssi = r[7].toInt(), heard = r[8].toInt() and 0xFF,
                    connectable = info and BleDevice.BI_CONNECTABLE != 0, scanRsp = info and BleDevice.BI_SCAN_RSP != 0,
                    anchor = info and BleDevice.BI_ANCHOR != 0, ad = r.copyOfRange(9, r.size),
                ))
                i += 1 + ln
            }
            return BlePacket(b1 and 3, ((b1 shr 2) and 3) + 1, b1 and 0x20 != 0, sv, devs)
        }
    }
}

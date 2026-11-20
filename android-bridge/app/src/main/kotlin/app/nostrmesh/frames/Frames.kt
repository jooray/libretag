package app.nostrmesh.frames

import app.nostrmesh.nostr.Nip04
import app.nostrmesh.nostr.NostrEvent
import app.nostrmesh.nostr.hex
import app.nostrmesh.nostr.isLowerHex
import app.nostrmesh.nostr.sha256
import app.nostrmesh.nostr.unhex
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

class FrameException(val code: String, detail: String = "") : Exception(if (detail.isEmpty()) code else "$code: $detail")

/**
 * Nostr Event Frames (docs/nostr-mesh-bridge.md in the LibreTag repo) and the Roadbeacon road frames.
 * Mirrors host/src/nostrmesh/frames.py + road.py; both run docs/vectors/nostr-frames-v1.json.
 */
object Frames {
    const val DATA_TYPE_NOSTR = 0xFF4E
    const val DATA_TYPE_ROAD = 0x0150
    const val DATA_TYPE_LIBRETAG = 0xFF4C
    const val CHANNEL_NOSTR = "#nostr"
    const val CHANNEL_ROAD = "#roadstr"
    const val CHANNEL_LIBRETAG = "#libretag"
    const val MAX_RADIO = 165
    const val MAX_BLE = 163
    const val MAX_REASSEMBLED = 4 * 161

    const val GENERIC = 0x00
    const val NOTE = 0x01
    const val ROAD_REPORT = 0x02
    const val ROAD_VOTE = 0x03
    const val DM4_FULL = 0x10
    const val DM4_REF = 0x11
    /**
     * dm4x: byte-identical to dm4, but the event carries a NIP-40 expiration at created_at +
     * [DM4X_TTL_S] (implicit, 0 B on air). The tracker's low-battery DM uses it.
     */
    const val DM4X_FULL = 0x12
    const val DM4X_REF = 0x13
    const val DM4X_TTL_S = 14L * 86400
    const val FRAG = 0x3E

    val DM_PROFILES = setOf(DM4_FULL, DM4_REF, DM4X_FULL, DM4X_REF)
    val REF_PROFILES = setOf(DM4_REF, DM4X_REF)

    const val INBOX_POLL_KIND = 21694

    val PROFILE_NAMES = mapOf(
        GENERIC to "generic", NOTE to "note", ROAD_REPORT to "road.report", ROAD_VOTE to "road.vote",
        DM4_FULL to "dm4.full", DM4_REF to "dm4.ref", DM4X_FULL to "dm4x.full", DM4X_REF to "dm4x.ref",
        FRAG to "frag",
    )

    /** Policy (uplink switches, max age) treats a dm4x frame like its dm4 twin. */
    fun policyName(profileName: String) = when (profileName) {
        "dm4x.full" -> "dm4.full"
        "dm4x.ref" -> "dm4.ref"
        else -> profileName
    }

    data class Decoded(val profile: Int, val event: NostrEvent) {
        val profileName get() = PROFILE_NAMES[profile] ?: "?"
        val policyName get() = policyName(profileName)
    }

    fun dm4Tags(rcptHex: String, createdAt: Long, expiring: Boolean): List<List<String>> =
        listOf(listOf("p", rcptHex)) + if (expiring) listOf(listOf("expiration", (createdAt + DM4X_TTL_S).toString())) else emptyList()

    /** null if the kind 4 event has no dm4 form; else whether it is the expiring (dm4x) shape. */
    fun dm4Shape(ev: NostrEvent): Boolean? {
        val t = ev.tags
        if (t.isEmpty() || t[0].size != 2 || t[0][0] != "p" || !isLowerHex(t[0][1], 32)) return null
        if (t.size == 1) return false
        if (t.size == 2 && t[1] == listOf("expiration", (ev.createdAt + DM4X_TTL_S).toString())) return true
        return null
    }

    private fun u32(b: ByteArray, off: Int): Long = ByteBuffer.wrap(b, off, 4).int.toLong() and 0xFFFFFFFFL
    private fun be32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()

    private fun text(b: ByteArray, allowNlTab: Boolean = true): String {
        val s = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            throw FrameException("NON_CANONICAL", "not UTF-8")
        }
        for (c in s) {
            val ok = !(c.code < 0x20 || c.code == 0x7F) || (allowNlTab && (c == '\n' || c == '\t'))
            if (!ok) throw FrameException("NON_CANONICAL", "control character")
        }
        return s
    }

    private fun need(f: ByteArray, n: Int, exact: Boolean = false) {
        if (f.size < n || (exact && f.size != n)) throw FrameException("BAD_LENGTH")
    }

    // ------------------------------------------------------------------ decode

    /** Frame -> event. Throws FrameException. [resolve] maps a 4-byte ref to candidate pubkeys. */
    fun decode(
        frame: ByteArray,
        dataType: Int = DATA_TYPE_NOSTR,
        resolve: ((ByteArray) -> List<ByteArray>)? = null,
        verify: Boolean = true,
        maxLen: Int = MAX_RADIO,
    ): Decoded {
        if (dataType == DATA_TYPE_ROAD) {
            val ev = Road.decode(frame)
            return finish(Decoded(if (ev.kind == Road.KIND_VOTE) ROAD_VOTE else ROAD_REPORT, ev), verify)
        }
        if (dataType != DATA_TYPE_NOSTR) throw FrameException("UNKNOWN_PROFILE")
        if (frame.isEmpty()) throw FrameException("BAD_LENGTH")
        val prof = frame[0].toInt() and 0x3F
        if (frame.size > maxLen || (frame.size > MAX_RADIO && prof !in DM_PROFILES)) {
            throw FrameException("BAD_LENGTH")
        }
        if ((frame[0].toInt() and 0xFF) shr 6 != 0) throw FrameException("BAD_VERSION")
        val ev: NostrEvent = when (prof) {
            NOTE -> {
                need(frame, 102)
                val m = frame[37].toInt() and 0xFF
                need(frame, 102 + m, exact = true)
                NostrEvent(frame.copyOfRange(1, 33).hex(), u32(frame, 33), 1, emptyList(),
                    text(frame.copyOfRange(38, 38 + m)), sig = frame.copyOfRange(38 + m, frame.size).hex())
            }
            GENERIC -> decodeGeneric(frame)
            ROAD_REPORT, ROAD_VOTE -> {
                val flags = if (prof == ROAD_VOTE) 0x08 else 0x00
                return finish(Decoded(prof, Road.decode(byteArrayOf(flags.toByte()) + frame.copyOfRange(1, frame.size))), verify)
            }
            DM4_FULL, DM4X_FULL -> {
                need(frame, 149 + 16)
                val ln = frame.size - 149
                if (ln % 16 != 0) throw FrameException("BAD_LENGTH")
                dm(frame.copyOfRange(1, 33), u32(frame, 33), frame.copyOfRange(37, 69), frame.copyOfRange(69, 85),
                    frame.copyOfRange(85, 85 + ln), frame.copyOfRange(85 + ln, frame.size), prof == DM4X_FULL)
            }
            DM4_REF, DM4X_REF -> {
                need(frame, 93 + 16)
                val ln = frame.size - 93
                if (ln % 16 != 0) throw FrameException("BAD_LENGTH")
                if (resolve == null) throw FrameException("UNKNOWN_REF")
                val senders = resolve(frame.copyOfRange(1, 5))
                val rcpts = resolve(frame.copyOfRange(9, 13))
                if (senders.isEmpty() || rcpts.isEmpty()) throw FrameException("UNKNOWN_REF")
                for (s in senders) for (r in rcpts) {
                    val e = dm(s, u32(frame, 5), r, frame.copyOfRange(13, 29), frame.copyOfRange(29, 29 + ln),
                        frame.copyOfRange(29 + ln, frame.size), prof == DM4X_REF)
                    if (!verify || e.verify()) return Decoded(prof, e)
                }
                throw FrameException("SIGNATURE_INVALID")
            }
            FRAG -> throw FrameException("FRAGMENT")
            else -> throw FrameException("UNKNOWN_PROFILE")
        }
        return finish(Decoded(prof, ev.withId()), verify)
    }

    private fun finish(d: Decoded, verify: Boolean): Decoded {
        if (verify && !d.event.verify()) throw FrameException("SIGNATURE_INVALID")
        return d
    }

    private fun dm(sender: ByteArray, ca: Long, rcpt: ByteArray, iv: ByteArray, ct: ByteArray, sig: ByteArray, expiring: Boolean) =
        NostrEvent(sender.hex(), ca, 4, dm4Tags(rcpt.hex(), ca, expiring), Nip04.content(ct, iv), sig = sig.hex()).withId()

    private fun decodeGeneric(f: ByteArray): NostrEvent {
        need(f, 1 + 2 + 32 + 4 + 1 + 1 + 64)
        val kind = ((f[1].toInt() and 0xFF) shl 8) or (f[2].toInt() and 0xFF)
        val ntags = f[39].toInt() and 0xFF
        if (ntags > 15) throw FrameException("BAD_LENGTH", "too many tags")
        val end = f.size - 64
        var i = 40
        val tags = mutableListOf<List<String>>()
        try {
            repeat(ntags) {
                val nitems = f[i++].toInt() and 0xFF
                if (nitems == 0) throw FrameException("NON_CANONICAL", "empty tag")
                val tag = mutableListOf<String>()
                repeat(nitems) {
                    when (f[i++].toInt()) {
                        0x00 -> {
                            val ln = f[i].toInt() and 0xFF
                            if (i + 1 + ln > end) throw FrameException("BAD_LENGTH")
                            tag.add(text(f.copyOfRange(i + 1, i + 1 + ln)))
                            i += 1 + ln
                        }
                        0x01 -> {
                            if (i + 32 > end) throw FrameException("BAD_LENGTH")
                            tag.add(f.copyOfRange(i, i + 32).hex()); i += 32
                        }
                        0x02 -> { tag.add(u32(f, i).toString()); i += 4 }
                        0x04 -> {
                            val c = f[i++].toInt() and 0xFF
                            if (c !in 0x21..0x7E) throw FrameException("NON_CANONICAL")
                            tag.add(c.toChar().toString())
                        }
                        else -> throw FrameException("NON_CANONICAL", "item type")
                    }
                }
                tags.add(tag)
            }
            val m = f[i].toInt() and 0xFF
            if (i + 1 + m != end) throw FrameException("BAD_LENGTH")
            return NostrEvent(f.copyOfRange(3, 35).hex(), u32(f, 35), kind, tags, text(f.copyOfRange(i + 1, i + 1 + m)),
                sig = f.copyOfRange(end, f.size).hex())
        } catch (e: IndexOutOfBoundsException) {
            throw FrameException("BAD_LENGTH")
        }
    }

    // ------------------------------------------------------------------ encode (DMs, the downlink profile)

    /**
     * A kind-4 event as dm4.ref / dm4x.ref (both keys as 4-byte refs) or dm4.full / dm4x.full; the
     * event's shape picks dm4 or dm4x. Size is checked by the caller.
     */
    fun encodeDm(ev: NostrEvent, ref: Boolean): ByteArray {
        val x = if (ev.kind == 4) dm4Shape(ev) else null
        val profile = when {
            x == true -> if (ref) DM4X_REF else DM4X_FULL
            else -> if (ref) DM4_REF else DM4_FULL
        }
        return encodeDm(ev, profile)
    }

    /** A kind-4 event in exactly [profile] (one of [DM_PROFILES]); refuses a shape that does not fit. */
    fun encodeDm(ev: NostrEvent, profile: Int): ByteArray {
        if (profile !in DM_PROFILES) throw FrameException("UNKNOWN_PROFILE")
        if (ev.kind != 4) throw FrameException("WRONG_KIND")
        if (!isLowerHex(ev.pubkey, 32) || !isLowerHex(ev.sig, 64) || ev.createdAt !in 0 until (1L shl 32)) {
            throw FrameException("MALFORMED_EVENT")
        }
        val x = dm4Shape(ev)
        if (x == null || x != (profile == DM4X_FULL || profile == DM4X_REF)) {
            throw FrameException("NON_CANONICAL", "kind 4 needs one p tag (dm4) or p + expiration at +14 d (dm4x)")
        }
        val (ct, iv) = Nip04.parse(ev.content) ?: throw FrameException("NON_CANONICAL_BASE64")
        val pk = ev.pubkey.unhex()!!
        val rcpt = ev.tags[0][1].unhex()!!
        val p = byteArrayOf(profile.toByte())
        val frame = if (profile in REF_PROFILES) {
            p + pk.copyOf(4) + be32(ev.createdAt) + rcpt.copyOf(4) + iv + ct + ev.sig.unhex()!!
        } else {
            p + pk + be32(ev.createdAt) + rcpt + iv + ct + ev.sig.unhex()!!
        }
        val back = decode(frame, resolve = { r ->
            listOf(pk, rcpt).filter { it.copyOf(4).contentEquals(r) }
        }, verify = false, maxLen = frame.size).event
        if (back.computeId() != ev.computeId()) throw FrameException("NON_CANONICAL", "does not rebuild")
        if (!ev.verify()) throw FrameException("SIGNATURE_INVALID")
        return frame
    }
}

/** Application fragments 0x3E | msg_id(2) | idx<<4|n | data, n <= 4, idx 15 = XOR parity. */
object Fragments {
    const val HEADER = 4

    fun msgId(frame: ByteArray): ByteArray = sha256(frame).copyOf(2)

    fun split(frame: ByteArray, maxLen: Int): List<ByteArray> {
        val room = maxLen - HEADER
        val n = (frame.size + room - 1) / room
        if (n < 2 || n > 4) throw FrameException("BAD_LENGTH", "$n fragments")
        val id = msgId(frame)
        return (0 until n).map { i ->
            byteArrayOf(Frames.FRAG.toByte()) + id + byteArrayOf(((i shl 4) or n).toByte()) +
                frame.copyOfRange(i * room, minOf(frame.size, (i + 1) * room))
        }
    }

    /** Collects fragments (at most 2 variants per index, 60 s, 32 messages). Parity is not used here. */
    class Reassembler(private val timeoutMs: Long = 60_000, private val maxMessages: Int = 32) {
        private class Msg(val t0: Long, val n: Int, val parts: MutableMap<Int, MutableList<ByteArray>> = mutableMapOf())
        private val msgs = LinkedHashMap<String, Msg>()

        @Synchronized
        fun feed(f: ByteArray, now: Long): ByteArray? {
            if (f.size <= HEADER || f[0].toInt() != Frames.FRAG) throw FrameException("BAD_LENGTH")
            val idx = (f[3].toInt() and 0xFF) shr 4
            val n = f[3].toInt() and 0x0F
            if (n !in 2..4 || (idx >= n && idx != 15)) throw FrameException("BAD_LENGTH")
            msgs.entries.removeAll { now - it.value.t0 > timeoutMs }
            val id = f.copyOfRange(1, 3)
            val key = id.hex() + n
            val m = msgs.getOrPut(key) { Msg(now, n) }
            while (msgs.size > maxMessages) msgs.remove(msgs.keys.first())
            val v = m.parts.getOrPut(idx) { mutableListOf() }
            val data = f.copyOfRange(HEADER, f.size)
            if (v.any { it.contentEquals(data) } || v.size >= 2) return null
            v.add(data)
            if ((0 until n).all { it in m.parts }) {
                var combos = listOf(ByteArray(0))
                for (i in 0 until n) combos = combos.flatMap { c -> m.parts[i]!!.map { c + it } }
                combos.firstOrNull { msgId(it).contentEquals(id) }?.let { msgs.remove(key); return it }
            }
            return null
        }
    }
}

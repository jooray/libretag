package app.libretag.proto

import java.security.MessageDigest
import java.util.UUID

/**
 * The console over Bluetooth (PROTOCOL.md 6.2, firmware 0.8.0). After a reboot a paired tag
 * advertises for 3 minutes: manufacturer data 0xFFFF, type 0x25, `left`, a salt and a 6 B tag only
 * a holder of its usb_key recognises. The app connects, the tag sends HELLO with a fresh nonce, the
 * app answers AUTH with its own nonce and a proof, the tag proves itself in READY, then every
 * console byte travels in sealed DATA frames. No Bluetooth pairing.
 */
object BleConsole {
    val SERVICE: UUID = UUID.fromString("4c540001-7a1e-4b0c-9d2f-6c6962726574")
    val RX: UUID = UUID.fromString("4c540002-7a1e-4b0c-9d2f-6c6962726574")
    val TX: UUID = UUID.fromString("4c540003-7a1e-4b0c-9d2f-6c6962726574")
    /** Client Characteristic Configuration descriptor */
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val COMPANY = 0xFFFF
    const val ADV_TYPE = 0x25
    const val ADV_LEN = 14
    const val VER = 0x01
    const val HELLO = 0x01
    const val AUTH = 0x02
    const val READY = 0x03
    const val DATA = 0x10
    const val NONCE_LEN = 16
    const val PROOF_LEN = 16
    const val MAC_LEN = 8
    /** console bytes in one DATA frame at ATT MTU 247 */
    const val DATA_MAX = 235
    const val MTU = 247
    const val A2T = 0
    const val T2A = 1
    /** the window after a boot, seconds */
    const val WINDOW_S = 180
    /** the firmware that has the console */
    const val FIRMWARE = "0.8.0"

    private fun advTag(usbKey: ByteArray, salt: ByteArray): ByteArray =
        LibreTag.hmac(usbKey, "LT2-bcon-adv".toByteArray(), salt).copyOf(6)

    /** The 14 B after company id 0xFFFF (what the tag advertises; for the tests and a fake tag). */
    fun adv(usbKey: ByteArray, salt: ByteArray, leftS: Int): ByteArray {
        require(salt.size == 6)
        val left = minOf(255, (maxOf(0, leftS) + 1) / 2)
        return byteArrayOf(ADV_TYPE.toByte(), left.toByte()) + salt + advTag(usbKey, salt)
    }

    /** a console advert of the tag with this usb_key */
    fun advMatch(usbKey: ByteArray, payload: ByteArray): Boolean =
        payload.size == ADV_LEN && payload[0] == ADV_TYPE.toByte() &&
            MessageDigest.isEqual(advTag(usbKey, payload.copyOfRange(2, 8)), payload.copyOfRange(8, 14))

    /** a console advert of some tag (no key needed): the scan filter */
    fun isConsoleAdvert(payload: ByteArray?): Boolean = payload != null && payload.size == ADV_LEN && payload[0] == ADV_TYPE.toByte()

    /** seconds until the window closes, from the advert's `left` byte (255: more than 508 s) */
    fun leftSeconds(payload: ByteArray): Int = (payload[1].toInt() and 0xFF) * 2

    /** who = 'a' (AUTH) or 't' (READY) */
    fun proof(usbKey: ByteArray, who: Char, tagNonce: ByteArray, appNonce: ByteArray): ByteArray {
        require(who == 'a' || who == 't')
        return LibreTag.hmac(usbKey, "LT2-bcon-$who".toByteArray(), tagNonce, appNonce).copyOf(PROOF_LEN)
    }

    fun auth(usbKey: ByteArray, tagNonce: ByteArray, appNonce: ByteArray): ByteArray =
        byteArrayOf(AUTH.toByte()) + appNonce + proof(usbKey, 'a', tagNonce, appNonce)

    /** the tag nonce from a HELLO frame, null when it is not one */
    fun helloNonce(f: ByteArray): ByteArray? =
        if (f.size == 2 + NONCE_LEN && f[0] == HELLO.toByte() && f[1] == VER.toByte()) f.copyOfRange(2, f.size) else null

    /** READY carries the tag's proof: true when it is the tag with this key */
    fun readyOk(usbKey: ByteArray, f: ByteArray, tagNonce: ByteArray, appNonce: ByteArray): Boolean =
        f.size == 1 + PROOF_LEN && f[0] == READY.toByte() &&
            MessageDigest.isEqual(f.copyOfRange(1, f.size), proof(usbKey, 't', tagNonce, appNonce))

    /** console bytes per DATA frame at this ATT MTU */
    fun chunk(mtu: Int): Int = minOf(DATA_MAX, mtu - 3 - 1 - MAC_LEN).coerceAtLeast(1)

    /** Session keys and frame counters of one connection. */
    class Session(usbKey: ByteArray, tagNonce: ByteArray, appNonce: ByteArray) {
        val kd: Array<ByteArray>
        val ctr = longArrayOf(0, 0)

        init {
            val ks = LibreTag.hmac(usbKey, "LT2-bcon-k".toByteArray(), tagNonce, appNonce)
            kd = arrayOf(LibreTag.hmac(ks, "LT2-bcon-a2t".toByteArray()), LibreTag.hmac(ks, "LT2-bcon-t2a".toByteArray()))
        }

        private fun mac(d: Int, c: Long, ct: ByteArray) =
            LibreTag.hmac(kd[d].copyOfRange(16, 32), LibreTag.u32(c), ct).copyOf(MAC_LEN)

        private fun iv(c: Long) = LibreTag.u32(c) + ByteArray(4)

        /** A DATA frame: 0x10 || ct || mac. Advances the counter of [d]. */
        fun seal(d: Int, pt: ByteArray): ByteArray {
            require(pt.isNotEmpty())
            val c = ctr[d]
            val ct = LibreTag.ctr(kd[d].copyOf(16), iv(c), pt)
            ctr[d] = (c + 1) and 0xFFFFFFFFL
            return byteArrayOf(DATA.toByte()) + ct + mac(d, c, ct)
        }

        /** The plaintext of the next frame of [d], or null (the connection ends). */
        fun open(d: Int, f: ByteArray): ByteArray? {
            if (f.size < 2 + MAC_LEN || f[0] != DATA.toByte()) return null
            val c = ctr[d]
            val ct = f.copyOfRange(1, f.size - MAC_LEN)
            if (!MessageDigest.isEqual(f.copyOfRange(f.size - MAC_LEN, f.size), mac(d, c, ct))) return null
            ctr[d] = (c + 1) and 0xFFFFFFFFL
            return LibreTag.ctr(kd[d].copyOf(16), iv(c), ct)
        }

        /** [bytes] as DATA frames for an ATT MTU */
        fun frames(bytes: ByteArray, mtu: Int): List<ByteArray> {
            val n = chunk(mtu)
            return (bytes.indices step n).map { seal(A2T, bytes.copyOfRange(it, minOf(bytes.size, it + n))) }
        }
    }
}

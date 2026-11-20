package app.libretag.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2 encryption (secp256k1 ECDH, HKDF-SHA256, ChaCha20, HMAC-SHA256), checked against the
 * official vectors (paulmillr/nip44). Used for NIP-46 requests to a remote signer; the backup copy
 * for the owner's npub is encrypted by the owner's signer, never here (we don't hold that key).
 */
object Nip44 {
    const val VERSION: Byte = 2
    private const val MIN_PLAINTEXT = 1
    private const val MAX_PLAINTEXT = 65535

    class Nip44Exception(msg: String) : Exception(msg)

    fun conversationKey(sk: ByteArray, peerXonly: ByteArray): ByteArray {
        if (sk.size != 32 || peerXonly.size != 32) throw Nip44Exception("bad key length")
        val shared = try {
            if (!Secp256k1.secKeyVerify(sk)) throw Nip44Exception("invalid secret key")
            Secp256k1.pubKeyTweakMul(byteArrayOf(2) + peerXonly, sk).copyOfRange(1, 33)
        } catch (e: Nip44Exception) {
            throw e
        } catch (e: Exception) {
            throw Nip44Exception("invalid public key")
        }
        return hmac("nip44-v2".toByteArray(), shared)
    }

    /** (chacha key, chacha nonce, hmac key) for one message nonce. */
    internal fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        if (conversationKey.size != 32) throw Nip44Exception("bad conversation key")
        if (nonce.size != 32) throw Nip44Exception("bad nonce")
        val okm = hkdfExpand(conversationKey, nonce, 76)
        return Triple(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), okm.copyOfRange(44, 76))
    }

    fun calcPaddedLen(len: Int): Int {
        if (len < 1) throw Nip44Exception("expected positive length")
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val u = plaintext.toByteArray(Charsets.UTF_8)
        if (u.size < MIN_PLAINTEXT || u.size > MAX_PLAINTEXT) throw Nip44Exception("invalid plaintext length")
        val out = ByteArray(2 + calcPaddedLen(u.size))
        out[0] = (u.size shr 8).toByte()
        out[1] = u.size.toByte()
        u.copyInto(out, 2)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        if (len < MIN_PLAINTEXT || len > MAX_PLAINTEXT || padded.size != 2 + calcPaddedLen(len)) throw Nip44Exception("invalid padding")
        return String(padded, 2, len, Charsets.UTF_8)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }): String {
        val (ck, cn, hk) = messageKeys(conversationKey, nonce)
        val ct = ChaCha20.xor(ck, cn, pad(plaintext))
        val mac = hmac(hk, nonce + ct)
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + nonce + ct + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        val (nonce, ct, mac) = split(payload) ?: throw Nip44Exception("invalid payload")
        val (ck, cn, hk) = messageKeys(conversationKey, nonce)
        if (!MessageDigest.isEqual(hmac(hk, nonce + ct), mac)) throw Nip44Exception("invalid MAC")
        return unpad(ChaCha20.xor(ck, cn, ct))
    }

    /**
     * Whether [content] looks like a NIP-44 v2 payload (base64, version byte 2, sizes in range),
     * without decrypting it. The npub backup copy is found among other app-data events this way.
     */
    fun isPayload(content: String): Boolean = split(content) != null

    private fun split(payload: String): Triple<ByteArray, ByteArray, ByteArray>? {
        if (payload.isEmpty() || payload[0] == '#') return null
        if (payload.length < 132 || payload.length > 87472) return null
        val raw = try {
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (raw.size < 99 || raw.size > 65603 || raw[0] != VERSION) return null
        return Triple(raw.copyOfRange(1, 33), raw.copyOfRange(33, raw.size - 32), raw.copyOfRange(raw.size - 32, raw.size))
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }
}

/** ChaCha20 (RFC 8439), 32-bit counter starting at 0, as NIP-44 uses it. */
internal object ChaCha20 {
    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))

    private fun le(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le(key, 4 * i)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le(nonce, 4 * i)
        val x = s.copyOf()
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
            x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
            x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[4 * i] = v.toByte(); out[4 * i + 1] = (v shr 8).toByte(); out[4 * i + 2] = (v shr 16).toByte(); out[4 * i + 3] = (v shr 24).toByte()
        }
        return out
    }

    fun xor(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val out = ByteArray(data.size)
        var counter = 0
        var off = 0
        while (off < data.size) {
            val ks = block(key, nonce, counter++)
            val n = minOf(64, data.size - off)
            for (i in 0 until n) out[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return out
    }
}

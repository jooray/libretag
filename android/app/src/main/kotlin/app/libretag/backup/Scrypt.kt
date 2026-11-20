package app.libretag.backup

import java.security.MessageDigest

/**
 * scrypt (RFC 7914) in plain Kotlin: PBKDF2-HMAC-SHA256, Salsa20/8, BlockMix, ROMix. HMAC is done by
 * hand on MessageDigest because javax.crypto refuses an empty key (the first RFC vector has one).
 * Memory is 128 * r * n bytes (64 MiB at n = 2^16, r = 8), time grows with n * r * p: call it off
 * the main thread.
 */
object Scrypt {
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0) { "n must be a power of two > 1" }
        require(r >= 1 && p >= 1 && dkLen >= 1)
        require(r.toLong() * p < (1 shl 30) && 32L * r * n <= Int.MAX_VALUE) { "parameters too large" }
        val blockLen = 128 * r
        val b = pbkdf2Sha256(password, salt, 1, p * blockLen)
        val words = blockLen / 4
        val x = IntArray(words)
        val v = IntArray(words * n)
        val tmp = IntArray(words)
        for (i in 0 until p) {
            val off = i * blockLen
            for (k in 0 until words) x[k] = le32(b, off + 4 * k)
            roMix(x, v, tmp, r, n)
            for (k in 0 until words) putLe32(b, off + 4 * k, x[k])
        }
        return pbkdf2Sha256(password, b, 1, dkLen)
    }

    /** PBKDF2 (RFC 8018) with HMAC-SHA256. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val h = Hmac(password)
        val out = ByteArray(dkLen)
        var block = 1
        var pos = 0
        while (pos < dkLen) {
            var u = h.mac(salt, byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            for (it in 1 until iterations) {
                u = h.mac(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val take = minOf(32, dkLen - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
            block++
        }
        return out
    }

    /** HMAC-SHA256 (RFC 2104), any key length including zero. */
    class Hmac(key: ByteArray) {
        private val ipad: ByteArray
        private val opad: ByteArray

        init {
            val k = (if (key.size > 64) MessageDigest.getInstance("SHA-256").digest(key) else key).copyOf(64)
            ipad = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
            opad = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        }

        fun mac(vararg parts: ByteArray): ByteArray {
            val inner = MessageDigest.getInstance("SHA-256")
            inner.update(ipad)
            parts.forEach { inner.update(it) }
            val outer = MessageDigest.getInstance("SHA-256")
            outer.update(opad)
            return outer.digest(inner.digest())
        }
    }

    private fun roMix(x: IntArray, v: IntArray, tmp: IntArray, r: Int, n: Int) {
        val words = x.size
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * words, words)
            blockMix(x, tmp, r)
        }
        for (i in 0 until n) {
            // Integerify: the first word of the last 64-byte block, little-endian, mod n
            val j = x[(2 * r - 1) * 16] and (n - 1)
            val base = j * words
            for (k in 0 until words) x[k] = x[k] xor v[base + k]
            blockMix(x, tmp, r)
        }
    }

    /** BlockMix_{Salsa20/8, r} in place on [b] (2r 64-byte blocks), [y] is scratch of the same size. */
    private fun blockMix(b: IntArray, y: IntArray, r: Int) {
        val x = IntArray(16)
        System.arraycopy(b, (2 * r - 1) * 16, x, 0, 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa208(x)
            // even blocks go to the first half, odd ones to the second
            val dst = (if (i % 2 == 0) i / 2 else r + i / 2) * 16
            System.arraycopy(x, 0, y, dst, 16)
        }
        System.arraycopy(y, 0, b, 0, 32 * r)
    }

    /** The Salsa20/8 core on 16 words, in place. */
    internal fun salsa208(b: IntArray) {
        val x = b.copyOf()
        for (round in 0 until 4) {
            x[4] = x[4] xor Integer.rotateLeft(x[0] + x[12], 7)
            x[8] = x[8] xor Integer.rotateLeft(x[4] + x[0], 9)
            x[12] = x[12] xor Integer.rotateLeft(x[8] + x[4], 13)
            x[0] = x[0] xor Integer.rotateLeft(x[12] + x[8], 18)
            x[9] = x[9] xor Integer.rotateLeft(x[5] + x[1], 7)
            x[13] = x[13] xor Integer.rotateLeft(x[9] + x[5], 9)
            x[1] = x[1] xor Integer.rotateLeft(x[13] + x[9], 13)
            x[5] = x[5] xor Integer.rotateLeft(x[1] + x[13], 18)
            x[14] = x[14] xor Integer.rotateLeft(x[10] + x[6], 7)
            x[2] = x[2] xor Integer.rotateLeft(x[14] + x[10], 9)
            x[6] = x[6] xor Integer.rotateLeft(x[2] + x[14], 13)
            x[10] = x[10] xor Integer.rotateLeft(x[6] + x[2], 18)
            x[3] = x[3] xor Integer.rotateLeft(x[15] + x[11], 7)
            x[7] = x[7] xor Integer.rotateLeft(x[3] + x[15], 9)
            x[11] = x[11] xor Integer.rotateLeft(x[7] + x[3], 13)
            x[15] = x[15] xor Integer.rotateLeft(x[11] + x[7], 18)
            x[1] = x[1] xor Integer.rotateLeft(x[0] + x[3], 7)
            x[2] = x[2] xor Integer.rotateLeft(x[1] + x[0], 9)
            x[3] = x[3] xor Integer.rotateLeft(x[2] + x[1], 13)
            x[0] = x[0] xor Integer.rotateLeft(x[3] + x[2], 18)
            x[6] = x[6] xor Integer.rotateLeft(x[5] + x[4], 7)
            x[7] = x[7] xor Integer.rotateLeft(x[6] + x[5], 9)
            x[4] = x[4] xor Integer.rotateLeft(x[7] + x[6], 13)
            x[5] = x[5] xor Integer.rotateLeft(x[4] + x[7], 18)
            x[11] = x[11] xor Integer.rotateLeft(x[10] + x[9], 7)
            x[8] = x[8] xor Integer.rotateLeft(x[11] + x[10], 9)
            x[9] = x[9] xor Integer.rotateLeft(x[8] + x[11], 13)
            x[10] = x[10] xor Integer.rotateLeft(x[9] + x[8], 18)
            x[12] = x[12] xor Integer.rotateLeft(x[15] + x[14], 7)
            x[13] = x[13] xor Integer.rotateLeft(x[12] + x[15], 9)
            x[14] = x[14] xor Integer.rotateLeft(x[13] + x[12], 13)
            x[15] = x[15] xor Integer.rotateLeft(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
}

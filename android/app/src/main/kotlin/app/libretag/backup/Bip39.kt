package app.libretag.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * BIP-39 mnemonics (English wordlist) as the backup phrase (SPEC.md 12.2 A). Only the word
 * encoding and its checksum: LibreTag never turns a phrase into a wallet seed (no PBKDF2 to
 * BIP-39 seed, no BIP-32). The phrase itself goes into scrypt as the 12.1 passphrase.
 *
 * Accepted: 12 or 24 words with a valid checksum. A BIP-85 child phrase (application 39',
 * English, 12 words) is an ordinary mnemonic, so it is accepted the same way.
 */
object Bip39 {
    /** SHA-256 of the official english.txt (2048 lines, LF), checked by the tests. */
    const val WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
    val LENGTHS = setOf(12, 24)

    val words: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("/app/libretag/backup/bip39-english.txt")
            ?: error("BIP-39 wordlist missing")
        stream.bufferedReader(Charsets.UTF_8).use { r -> r.readLines().map { it.trim() }.filter { it.isNotEmpty() } }
            .also { check(it.size == 2048) { "BIP-39 wordlist has ${it.size} words" } }
    }
    private val index: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }

    enum class Problem { EMPTY, WRONG_LENGTH, UNKNOWN_WORD, BAD_CHECKSUM }

    sealed class Check {
        /** [phrase]: the canonical form (lowercase words, single spaces), the passphrase for 12.1 */
        data class Ok(val phrase: String) : Check()
        /** [word]: the first unknown word, for UNKNOWN_WORD; [count]: how many words were typed */
        data class Bad(val problem: Problem, val word: String? = null, val count: Int = 0) : Check()
    }

    /** The 12.1 normalisation (NFKC, whitespace runs to one space, trimmed), then lowercase. */
    fun normalize(s: String): String = KeyBackup.normalize(s).lowercase(Locale.ROOT)

    fun split(s: String): List<String> = normalize(s).let { if (it.isEmpty()) emptyList() else it.split(' ') }

    fun check(s: String): Check {
        val w = split(s)
        if (w.isEmpty()) return Check.Bad(Problem.EMPTY)
        w.firstOrNull { it !in index }?.let { return Check.Bad(Problem.UNKNOWN_WORD, it, w.size) }
        if (w.size !in LENGTHS) return Check.Bad(Problem.WRONG_LENGTH, count = w.size)
        if (toEntropy(w) == null) return Check.Bad(Problem.BAD_CHECKSUM, count = w.size)
        return Check.Ok(w.joinToString(" "))
    }

    /** Words for [entropy] (16..32 bytes, a multiple of 4), with the SHA-256 checksum bits. */
    fun fromEntropy(entropy: ByteArray): String {
        require(entropy.size in 16..32 && entropy.size % 4 == 0)
        val csBits = entropy.size * 8 / 32
        val bits = BooleanArray(entropy.size * 8 + csBits)
        for (i in 0 until entropy.size * 8) bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        val h = sha256(entropy)
        for (i in 0 until csBits) bits[entropy.size * 8 + i] = (h[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        return (0 until bits.size / 11).joinToString(" ") { g ->
            var v = 0
            for (j in 0 until 11) v = (v shl 1) or (if (bits[g * 11 + j]) 1 else 0)
            words[v]
        }
    }

    /** The entropy of a mnemonic, or null for unknown words, an unusual length or a bad checksum. */
    fun toEntropy(phrase: String): ByteArray? = toEntropy(split(phrase))

    private fun toEntropy(w: List<String>): ByteArray? {
        if (w.size % 3 != 0 || w.size !in 12..24) return null
        val bits = BooleanArray(w.size * 11)
        w.forEachIndexed { g, word ->
            val v = index[word] ?: return null
            for (j in 0 until 11) bits[g * 11 + j] = (v shr (10 - j)) and 1 == 1
        }
        val csBits = bits.size / 33
        val entropy = ByteArray((bits.size - csBits) / 8)
        for (i in 0 until entropy.size * 8) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        val h = sha256(entropy)
        for (i in 0 until csBits) {
            if (bits[entropy.size * 8 + i] != ((h[i / 8].toInt() shr (7 - i % 8)) and 1 == 1)) return null
        }
        return entropy
    }

    /** A fresh 12-word phrase (128 bits). */
    fun generate(random: SecureRandom = SecureRandom()): String = fromEntropy(ByteArray(16).also { random.nextBytes(it) })

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}

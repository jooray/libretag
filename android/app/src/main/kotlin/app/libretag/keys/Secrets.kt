package app.libretag.keys

import app.libretag.data.Tracker
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps secrets (K0, shared V_e/C_e, mailbox event keys, backup-derived keys, the NIP-46 client key)
 * with an AES-256-GCM key that never leaves Android Keystore. Stored form: `ks1:` + base64(iv || ct).
 * A value without the prefix is a plain value from before 0.8.0; [open] passes it through and the
 * stores rewrite it wrapped ("migration on first start").
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray   // iv || ciphertext+tag
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM over any [SecretKey]; the Keystore one in the app, a software key in tests. */
class AesGcmBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key()) // Keystore picks the IV (randomized encryption required)
        return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV + 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV))
        return c.doFinal(sealed, IV, sealed.size - IV)
    }

    companion object { const val IV = 12 }
}

object Secrets {
    const val PREFIX = "ks1:"

    /** null until [install]; then every store wraps with it. Without a box values stay plain (logged). */
    @Volatile var box: SecretBox? = null

    fun isWrapped(s: String?) = s != null && s.startsWith(PREFIX)

    /** Wrap a string value; empty and already wrapped values pass unchanged. */
    fun wrap(plain: String, b: SecretBox? = box): String {
        if (plain.isEmpty() || isWrapped(plain) || b == null) return plain
        return synchronized(cache) {
            if (cache.size > 256) cache.clear()
            cache[plain] ?: (PREFIX + Base64.getEncoder().encodeToString(b.seal(plain.toByteArray(Charsets.UTF_8)))).also { cache[plain] = it }
        }
    }

    /**
     * Unwrap a stored value. A plain (legacy) value comes back as it is. A wrapped value that can't
     * be opened (Keystore key gone) also comes back as it is, so nothing gets overwritten with an
     * empty key; the tag then can't be read until it is restored from the backup.
     */
    fun unwrap(stored: String, b: SecretBox? = box): String {
        if (!isWrapped(stored)) return stored
        val bb = b ?: return stored
        return runCatching {
            String(bb.open(Base64.getDecoder().decode(stored.substring(PREFIX.length))), Charsets.UTF_8)
        }.getOrNull()?.also { synchronized(cache) { cache[it] = stored } } ?: stored
    }

    /** plain -> wrapped, so stores that rewrite everything on each change don't call Keystore each time */
    private val cache = HashMap<String, String>()

    fun clearCache() = synchronized(cache) { cache.clear() }

    // ---- per type

    fun sealTracker(t: Tracker, b: SecretBox? = box): Tracker =
        if (b == null) t else t.copy(keyHex = wrap(t.keyHex, b), sharedViewHex = wrap(t.sharedViewHex, b), sharedCmdHex = wrap(t.sharedCmdHex, b),
            sharedUsbHex = wrap(t.sharedUsbHex, b))

    fun openTracker(t: Tracker, b: SecretBox? = box): Tracker =
        t.copy(keyHex = unwrap(t.keyHex, b), sharedViewHex = unwrap(t.sharedViewHex, b), sharedCmdHex = unwrap(t.sharedCmdHex, b),
            sharedUsbHex = unwrap(t.sharedUsbHex, b))

    /** a tracker still stored in plain form (needs migration) */
    fun trackerNeedsWrap(stored: Tracker): Boolean =
        listOf(stored.keyHex, stored.sharedViewHex, stored.sharedCmdHex, stored.sharedUsbHex).any { it.isNotEmpty() && !isWrapped(it) }

    /**
     * Key-value migration (SharedPreferences): every plain value under [keys] is rewritten wrapped.
     * Returns how many were migrated. Pure over [get]/[put] so it runs in unit tests.
     */
    fun migrate(keys: List<String>, get: (String) -> String?, put: (String, String) -> Unit, b: SecretBox? = box): Int {
        if (b == null) return 0
        var n = 0
        for (k in keys) {
            val v = get(k) ?: continue
            if (v.isEmpty() || isWrapped(v)) continue
            put(k, wrap(v, b))
            n++
        }
        return n
    }
}

/** The Android Keystore key (created on first use). */
object KeystoreKey {
    const val ALIAS = "libretag-secrets-v1"

    fun get(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    /** A box over the Keystore key, or null when this phone's Keystore doesn't work. */
    fun box(): SecretBox? = runCatching {
        val k = get()
        AesGcmBox { k }.also { b -> check(String(b.open(b.seal("ok".toByteArray()))) == "ok") }
    }.getOrNull()
}

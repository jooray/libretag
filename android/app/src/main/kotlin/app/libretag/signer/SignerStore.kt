package app.libretag.signer

import android.content.Context
import app.libretag.backup.OwnerSigner
import app.libretag.nostr.unhex

/**
 * The owner's signer this app is signed in with (for the backup copy to the owner's npub):
 * NIP-55 (a signer app on this phone) or NIP-46 (bunker / nostrconnect). Kept in its own
 * SharedPreferences, excluded from Android backups like the rest; the NIP-46 client key is a
 * throwaway key that only lets this app talk to the signer, which still decides every request.
 */
class SignerStore(context: Context) {
    private val ctx = context.applicationContext
    private val p = ctx.getSharedPreferences("signer", Context.MODE_PRIVATE)

    init { app.libretag.keys.Secrets.migrate(listOf("client"), { p.getString(it, null) }, { k, v -> p.edit().putString(k, v).apply() }) }

    sealed class Session(val pubkeyHex: String) {
        class Local(pubkeyHex: String, val pkg: String) : Session(pubkeyHex)
        class Remote(pubkeyHex: String, val signerPubkey: String, val clientSkHex: String, val relays: List<String>) : Session(pubkeyHex)
    }

    val session: Session?
        get() {
            val pk = p.getString("pubkey", null) ?: return null
            return when (p.getString("kind", null)) {
                "nip55" -> Session.Local(pk, p.getString("pkg", null) ?: return null)
                "nip46" -> Session.Remote(pk, p.getString("signer", null) ?: return null, p.getString("client", null)?.let { app.libretag.keys.Secrets.unwrap(it) } ?: return null,
                    p.getString("relays", null)?.split(' ')?.filter { it.isNotBlank() } ?: return null)
                else -> null
            }
        }

    fun saveLocal(pubkeyHex: String, pkg: String) {
        closeLink()
        p.edit().clear().putString("kind", "nip55").putString("pubkey", pubkeyHex).putString("pkg", pkg).apply()
    }

    fun saveRemote(pubkeyHex: String, signerPubkey: String, clientSkHex: String, relays: List<String>, link: Nip46Link) {
        closeLink()
        p.edit().clear().putString("kind", "nip46").putString("pubkey", pubkeyHex).putString("signer", signerPubkey)
            .putString("client", app.libretag.keys.Secrets.wrap(clientSkHex)).putString("relays", relays.joinToString(" ")).apply()
        cached = link
    }

    fun signOut() {
        closeLink()
        p.edit().clear().apply()
    }

    @Volatile private var cached: Nip46Link? = null

    private fun closeLink() {
        cached?.close()
        cached = null
    }

    /** A signer for the current session, or null when signed out. */
    fun signer(): OwnerSigner? = when (val s = session) {
        null -> null
        is Session.Local -> Nip55Signer(ctx, s.pkg, s.pubkeyHex)
        is Session.Remote -> {
            val link = cached ?: Nip46Link(nip46Http, s.clientSkHex.unhex()!!, s.relays).also { cached = it }
            Nip46Signer(link, s.signerPubkey, s.pubkeyHex)
        }
    }
}

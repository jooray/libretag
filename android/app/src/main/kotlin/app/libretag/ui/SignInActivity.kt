package app.libretag.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import androidx.lifecycle.lifecycleScope
import app.libretag.R
import app.libretag.backup.SignerException
import app.libretag.nostr.NostrEvent
import app.libretag.nostr.hex
import app.libretag.signer.Nip46
import app.libretag.signer.Nip46Link
import app.libretag.signer.Nip46Signer
import app.libretag.signer.Nip55
import app.libretag.signer.Nip55Signer
import app.libretag.signer.SignerHost
import app.libretag.signer.nip46Http
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sign in with Nostr for the backup copy to the owner's npub (SPEC.md 12.2 B). A signer app on
 * this phone (NIP-55) comes first; then a signer on another device by nostrconnect:// (QR, copy,
 * open in a signer here) and a pasted bunker:// link (NIP-46). Nothing else is asked for: the
 * signer encrypts, decrypts and signs, and keeps the key.
 */
class SignInActivity : ScreenActivity() {
    private var busy: String? = null
    private var authUrl: String? = null
    private var expired = false
    private var clientSk = Nip46.newClientKey()
    private var secret = Nip46.newSecret()
    private var link: Nip46Link? = null
    private var waitJob: Job? = null
    private var done = false
    private var bunkerText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignerHost.attach(this)
        setTitles(getString(R.string.si_title))
        startConnectWait()
        render()
    }

    override fun onDestroy() {
        if (!done) link?.close()
        super.onDestroy()
    }

    private fun newLink(sk: ByteArray, relays: List<String>) =
        Nip46Link(nip46Http, sk, relays) { url -> runOnUiThread { authUrl = url; render() } }

    private fun connectUri() = Nip46.nostrConnectUri(NostrEvent.xonly(clientSk).hex(), CONNECT_RELAYS, secret, getString(R.string.app_name))

    private fun startConnectWait() {
        waitJob?.cancel()
        link?.close()
        expired = false
        val l = newLink(clientSk, CONNECT_RELAYS)
        link = l
        waitJob = lifecycleScope.launch {
            val signerPk = l.awaitConnect(secret, CONNECT_TIMEOUT_MS)
            if (signerPk == null) { expired = true; render(); return@launch }
            busy = getString(R.string.si_waiting); render()
            try {
                val s = Nip46Signer.withOwner(l, signerPk)
                app.signers.saveRemote(s.pubkeyHex, signerPk, clientSk.hex(), CONNECT_RELAYS, l)
                afterSignIn()
            } catch (e: SignerException) {
                fail(e)
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        para(getString(R.string.si_text))
        busy?.let {
            para(it)
            content.addView(LinearProgressIndicator(this).apply { isIndeterminate = true }, lp().margins(l = dp(16), r = dp(16), b = dp(8)))
        }
        authUrl?.let { url -> pad(hbox { addView(button(getString(R.string.si_auth), Btn.FILLED) { openUrl(url) }) }) }

        val signers = Nip55.signers(this)
        if (signers.isEmpty()) para(getString(R.string.si_no_signer))
        else pad(FlowRow(this).apply {
            signers.forEach { sa ->
                addView(button(getString(R.string.si_use, sa.label), Btn.FILLED, R.drawable.ic_key) { local(sa.packageName) }.apply { isEnabled = busy == null })
            }
        })

        hr()
        h3(getString(R.string.si_remote))
        if (expired) {
            para(getString(R.string.si_expired))
        } else {
            para(getString(R.string.si_remote_text))
            val uri = connectUri()
            content.addView(qrImage(uri), lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        pad(FlowRow(this).apply {
            if (!expired) {
                val uri = connectUri()
                addView(button(getString(R.string.copy), Btn.OUTLINED, R.drawable.ic_copy) { copyText("nostrconnect", uri) })
                if (canOpen(uri)) addView(button(getString(R.string.si_open), Btn.OUTLINED) { openLink(uri) })
            }
            addView(button(getString(R.string.si_new_code), Btn.TEXT) {
                clientSk = Nip46.newClientKey(); secret = Nip46.newSecret(); authUrl = null
                startConnectWait(); render()
            })
        })

        hr()
        h3(getString(R.string.si_bunker))
        val field = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(bunkerText)
        }
        pad(OutlinedField(this).apply { hint = getString(R.string.si_bunker_hint); addView(field) })
        pad(hbox {
            addView(button(getString(R.string.si_connect), Btn.OUTLINED) {
                bunkerText = field.text?.toString().orEmpty()
                bunker(bunkerText)
            }.apply { isEnabled = busy == null })
        })
    }

    private fun canOpen(uri: String): Boolean = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)), 0).isNotEmpty()
    }.getOrDefault(false)

    private fun openLink(uri: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))) }
    }

    private fun local(pkg: String) {
        busy = getString(R.string.si_waiting); render()
        lifecycleScope.launch {
            try {
                val (pk, signerPkg) = Nip55Signer.signIn(pkg)
                app.signers.saveLocal(pk, signerPkg)
                afterSignIn()
            } catch (e: SignerException) {
                fail(e)
            }
        }
    }

    private fun bunker(raw: String) {
        val b = Nip46.parseBunker(raw)
        if (b == null) { content.snack(getString(R.string.si_bunker_bad)); return }
        busy = getString(R.string.si_waiting); render()
        val sk = Nip46.newClientKey()
        val l = newLink(sk, b.relays)
        lifecycleScope.launch {
            try {
                val s = Nip46Signer.connectBunker(l, b)
                waitJob?.cancel(); link?.close()
                link = l
                app.signers.saveRemote(s.pubkeyHex, b.signerPubkey, sk.hex(), b.relays, l)
                afterSignIn()
            } catch (e: SignerException) {
                l.close()
                fail(e)
            }
        }
    }

    private suspend fun afterSignIn() {
        done = true
        waitJob?.cancel()
        busy = getString(R.string.si_looking); render()
        val added = app.backup.signedIn()
        val msg = if (added > 0) resources.getQuantityString(R.plurals.backup_restored, added, added) else getString(R.string.si_done)
        startActivity(Intent(this, BackupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(BackupActivity.EXTRA_MESSAGE, msg))
        finish()
    }

    private fun fail(e: SignerException) {
        busy = null
        render()
        content.snack(getString(if (e.reason == SignerException.Reason.REJECTED) R.string.si_rejected else R.string.si_unavailable))
    }

    companion object {
        /** relays for nostrconnect: ours first (its allowlist takes kind 24133 from anyone), then two public ones */
        val CONNECT_RELAYS = listOf("wss://nostr.cypherpunk.today", "wss://relay.primal.net")
        const val CONNECT_TIMEOUT_MS = 10 * 60_000L
    }
}

package app.libretag.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.libretag.R
import app.libretag.backup.BackupSync
import app.libretag.signer.SignerHost
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/**
 * Settings > Backup (SPEC.md 12.2): the copy for the owner's npub (sign in with Nostr) first, then
 * the recovery phrase. Shows when each copy was last saved and what stops it. "Save now" runs
 * here, with a screen open, so a signer that needs the user can ask.
 */
class BackupActivity : ScreenActivity() {
    private val sync get() = app.backup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignerHost.attach(this)
        setTitles(getString(R.string.menu_backup))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { sync.state.collect { render(it) } }
        }
        showMessage(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showMessage(intent)
    }

    private fun showMessage(i: Intent?) {
        val m = i?.getStringExtra(EXTRA_MESSAGE) ?: return
        i.removeExtra(EXTRA_MESSAGE)
        content.post { content.snack(m, long = true) }
    }

    private fun status(c: BackupSync.CopyState): String {
        val parts = mutableListOf(
            if (c.savedAt > 0) getString(R.string.bk_saved, Format.at(this, c.savedAt), c.relays) else getString(R.string.bk_not_saved),
        )
        when (c.problem) {
            BackupSync.Problem.SIGNER -> parts += getString(R.string.bk_problem_signer)
            BackupSync.Problem.OFFLINE -> parts += getString(R.string.bk_problem_offline)
            BackupSync.Problem.UNREADABLE -> parts += getString(R.string.bk_problem_unreadable)
            BackupSync.Problem.DELETED_ELSEWHERE -> parts += getString(R.string.bk_problem_deleted)
            BackupSync.Problem.FAILED -> parts += getString(R.string.bk_problem_failed)
            null -> if (c.pending && c.savedAt > 0) parts += getString(R.string.bk_pending)
        }
        return parts.joinToString("\n")
    }

    private fun render(s: BackupSync.State) {
        content.removeAllViews()
        para(getString(R.string.bk_intro))
        if (s.busy) content.addView(LinearProgressIndicator(this).apply { isIndeterminate = true }, lp().margins(l = dp(16), r = dp(16), t = dp(4), b = dp(4)))

        // B: the npub copy, the easy choice first
        val session = app.signers.session
        content.addView(card(getString(R.string.bk_npub_title), R.drawable.ic_key) {
            if (session == null) {
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.bk_npub_text), R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
                addView(FlowRow(this@BackupActivity).apply {
                    addView(button(getString(R.string.bk_signin), Btn.FILLED) { startActivity(Intent(this@BackupActivity, SignInActivity::class.java)) })
                })
            } else {
                val npub = app.nostrNpub(session.pubkeyHex) ?: session.pubkeyHex
                addView(text(R.style.TextAppearance_LibreTag_Status, getString(R.string.bk_signed_in_as, npub.take(12) + "…" + npub.takeLast(6)), R.color.ink2), lp().margins(t = dp(6)))
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, status(s.npub), R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
                addView(FlowRow(this@BackupActivity).apply {
                    addView(button(getString(R.string.bk_save_now), Btn.TONAL) { saveNow() }.apply { isEnabled = !s.busy })
                    addView(button(getString(R.string.bk_signout), Btn.TEXT) { signOut() }.apply { isEnabled = !s.busy })
                })
            }
        })

        // A: the recovery phrase
        content.addView(card(getString(R.string.bk_phrase_title), R.drawable.ic_cloud) {
            if (!s.phrase.on) {
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.bk_phrase_text), R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
                addView(FlowRow(this@BackupActivity).apply {
                    addView(button(getString(R.string.bk_phrase_create), Btn.FILLED) { startActivity(PhraseActivity.create(this@BackupActivity)) })
                    addView(button(getString(R.string.bk_phrase_restore), Btn.OUTLINED) { startActivity(PhraseActivity.restore(this@BackupActivity)) })
                })
            } else {
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.bk_phrase_on), R.color.ink2), lp().margins(t = dp(6)))
                addView(text(R.style.TextAppearance_LibreTag_BodyMedium, status(s.phrase), R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
                addView(FlowRow(this@BackupActivity).apply {
                    addView(button(getString(R.string.bk_save_now), Btn.TONAL) { saveNow() }.apply { isEnabled = !s.busy })
                    addView(button(getString(R.string.bk_phrase_restore), Btn.TEXT) { startActivity(PhraseActivity.restore(this@BackupActivity)) })
                    addView(button(getString(R.string.bk_phrase_delete), Btn.TEXT) { deletePhrase() }.apply { isEnabled = !s.busy })
                })
            }
        })

        if (s.notHere.isNotEmpty()) {
            para(resources.getQuantityString(R.plurals.bk_not_here, s.notHere.size, s.notHere.size))
            pad(hbox { addView(button(getString(R.string.bk_add_them), Btn.OUTLINED, R.drawable.ic_download) {
                val n = sync.import(s.notHere)
                content.snack(resources.getQuantityString(R.plurals.backup_restored, n, n))
                sync.schedule(2_000)
            }) })
        }

        if (sync.legacy() != null) content.addView(card(getString(R.string.bk_legacy_title), R.drawable.ic_alert) {
            addView(text(R.style.TextAppearance_LibreTag_BodyMedium, getString(R.string.bk_legacy_text), R.color.ink2), lp().margins(t = dp(6), b = dp(12)))
            addView(FlowRow(this@BackupActivity).apply {
                addView(button(getString(R.string.bk_legacy_delete), Btn.CRIT_OUTLINED) {
                    lifecycleScope.launch {
                        val ok = sync.deleteLegacy()
                        content.snack(getString(if (ok) R.string.backup_deleted else R.string.backup_failed))
                        render(sync.state.value)
                    }
                })
            })
        })
        para(getString(R.string.bk_risk))
    }

    private fun saveNow() {
        lifecycleScope.launch { sync.save() }
    }

    private fun signOut() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.bk_signout_title).setMessage(R.string.bk_signout_text)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.bk_signout_delete) { _, _ ->
                lifecycleScope.launch { if (!sync.signOut(delete = true)) content.snack(getString(R.string.backup_failed)) }
            }
            .setPositiveButton(R.string.bk_signout) { _, _ -> lifecycleScope.launch { sync.signOut(delete = false) } }
            .show()
    }

    private fun deletePhrase() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.bk_phrase_delete).setMessage(R.string.bk_phrase_delete_text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.bk_phrase_delete) { _, _ ->
                lifecycleScope.launch {
                    content.snack(getString(if (sync.deletePhraseCopy()) R.string.backup_deleted else R.string.backup_failed))
                }
            }.show()
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
